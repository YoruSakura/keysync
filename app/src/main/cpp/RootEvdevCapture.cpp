#include <jni.h>

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#ifndef REL_WHEEL_HI_RES
#define REL_WHEEL_HI_RES 0x0b
#endif

namespace {

constexpr char kLogTag[] = "RootEvdevCapture";
constexpr int kMaxEpollEvents = 16;
constexpr int kWakeMarker = -1;
constexpr int kErrorNoMatchedNodes = 1001;
constexpr int kErrorNoKeyboardNode = 1002;
constexpr int kErrorNoMouseMotionNode = 1003;
constexpr int kErrorNoMouseButtonNode = 1004;
constexpr int kWheelUnitsPerNotch = 120;
constexpr unsigned short kBusVirtual = 0x06;
constexpr size_t kBitsPerWord = sizeof(unsigned long) * 8;

template <size_t MaxBit>
using BitArray = std::array<unsigned long, (MaxBit / kBitsPerWord) + 1>;

std::atomic<bool> g_running(false);
std::atomic<bool> g_capture_enabled(false);
std::atomic<int> g_wake_fd(-1);

struct Device {
    int fd = -1;
    bool keyboard = false;
    bool mouse_motion = false;
    bool mouse_buttons = false;
    bool mouse_scroll = false;
    bool direct_touch = false;
    int relative_x = 0;
    int relative_y = 0;
    int relative_wheel = 0;
    int relative_wheel_hi_res = 0;
    bool dropping_events = false;
    std::string path;
    std::string name;
};

struct AllowedDevice {
    std::string name;
    int vendor_id = 0;
    int product_id = 0;
};

bool test_bit(const unsigned long *bits, int bit) {
    return (bits[bit / static_cast<int>(kBitsPerWord)] &
            (1UL << (bit % static_cast<int>(kBitsPerWord)))) != 0;
}

template <size_t WordCount>
bool read_bits(
        int fd,
        int event_type,
        std::array<unsigned long, WordCount> &bits) {
    bits.fill(0);
    return ioctl(fd, EVIOCGBIT(event_type, sizeof(bits)), bits.data()) >= 0;
}

bool is_direct_touch_device(int fd, const BitArray<EV_MAX> &event_bits) {
    BitArray<INPUT_PROP_MAX> property_bits{};
    if (ioctl(fd, EVIOCGPROP(sizeof(property_bits)), property_bits.data()) >= 0 &&
        test_bit(property_bits.data(), INPUT_PROP_DIRECT)) {
        return true;
    }

    if (!test_bit(event_bits.data(), EV_ABS)) return false;
    BitArray<ABS_MAX> absolute_bits{};
    if (!read_bits(fd, EV_ABS, absolute_bits)) return false;
    return test_bit(absolute_bits.data(), ABS_MT_POSITION_X) ||
           test_bit(absolute_bits.data(), ABS_MT_POSITION_Y);
}

bool classify_device(
        int fd,
        bool &keyboard,
        bool &mouse_motion,
        bool &mouse_buttons,
        bool &mouse_scroll,
        bool &direct_touch) {
    BitArray<EV_MAX> event_bits{};
    if (!read_bits(fd, 0, event_bits)) return false;

    BitArray<KEY_MAX> key_bits{};
    if (test_bit(event_bits.data(), EV_KEY)) {
        read_bits(fd, EV_KEY, key_bits);
    }

    // KeySync only needs a deterministic keyboard source for gameplay keys.
    // Requiring unrelated keys such as Z/Enter rejected Xiaomi pogo keyboards
    // whose vendor bridge exposes an incomplete evdev capability bitmap even
    // though it delivers the WASD cluster correctly.
    keyboard = test_bit(event_bits.data(), EV_KEY) &&
               test_bit(key_bits.data(), KEY_W) &&
               test_bit(key_bits.data(), KEY_A) &&
               test_bit(key_bits.data(), KEY_S) &&
               test_bit(key_bits.data(), KEY_D);
    direct_touch = is_direct_touch_device(fd, event_bits);
    // A matched keyboard cover may expose keyboard keys and a touchpad from
    // one evdev node. Keep the keyboard half usable, but never accept a direct
    // touch node which does not also have full keyboard capabilities.
    if (direct_touch && !keyboard) return false;

    BitArray<REL_MAX> relative_bits{};
    if (test_bit(event_bits.data(), EV_REL)) {
        read_bits(fd, EV_REL, relative_bits);
    }
    mouse_motion = test_bit(event_bits.data(), EV_REL) &&
                   test_bit(relative_bits.data(), REL_X) &&
                   test_bit(relative_bits.data(), REL_Y);
    mouse_scroll = test_bit(event_bits.data(), EV_REL) &&
                   (test_bit(relative_bits.data(), REL_WHEEL) ||
                    test_bit(relative_bits.data(), REL_WHEEL_HI_RES));
    mouse_buttons = test_bit(event_bits.data(), EV_KEY) &&
                    test_bit(key_bits.data(), BTN_LEFT);
    return keyboard || mouse_motion || mouse_buttons || mouse_scroll;
}

bool is_virtual_input_device(int fd) {
    input_id id{};
    return ioctl(fd, EVIOCGID, &id) >= 0 && id.bustype == kBusVirtual;
}

bool has_xiaomi_private_keyboard_name(const std::string &name) {
    std::string normalized = name;
    std::transform(
            normalized.begin(),
            normalized.end(),
            normalized.begin(),
            [](unsigned char character) {
                return static_cast<char>(std::tolower(character));
            });
    // Xiaomi's pogo-pin accessories do not consistently expose "Xiaomi" in
    // their evdev names. Newer covers use PTMS-family names (for example the
    // paired "Mi PTMS3 Mouse" reported by Android), while some generations
    // use the Nanosic controller name. Keep this deliberately narrower than
    // accepting every BUS_VIRTUAL keyboard: uinput_nav is a generic MIUI
    // device which is present even when no keyboard cover is attached.
    const bool explicitly_keyboard =
            normalized.find("keyboard") != std::string::npos ||
            normalized.find("kbd") != std::string::npos;
    const bool mi_named_keyboard =
            explicitly_keyboard &&
            (normalized.rfind("mi ", 0) == 0 ||
             normalized.find(" mi ") != std::string::npos);
    return normalized.find("xiaomi") != std::string::npos ||
           normalized.find("nanosic") != std::string::npos ||
           (normalized.find("ptms") != std::string::npos && explicitly_keyboard) ||
           mi_named_keyboard;
}

bool is_allowed_device(
        int fd,
        const std::string &name,
        const std::vector<AllowedDevice> &allowed_devices) {
    input_id id{};
    if (ioctl(fd, EVIOCGID, &id) < 0) return false;
    return std::any_of(
            allowed_devices.begin(),
            allowed_devices.end(),
            [&name, &id](const AllowedDevice &allowed) {
                const bool has_stable_hardware_id =
                        allowed.vendor_id != 0 || allowed.product_id != 0;
                const bool hardware_id_matches =
                        has_stable_hardware_id &&
                        allowed.vendor_id == static_cast<int>(id.vendor) &&
                        allowed.product_id == static_cast<int>(id.product);
                const bool name_matches = !allowed.name.empty() && allowed.name == name;
                // Android can expose one logical InputDevice for several evdev
                // nodes and give that logical device a different display name.
                // VID/PID therefore takes precedence; exact name remains the
                // fallback for Bluetooth devices which report zero IDs.
                return hardware_id_matches || name_matches;
            });
}

bool has_capability(
        const std::vector<Device> &devices,
        bool Device::*capability) {
    return std::any_of(
            devices.begin(),
            devices.end(),
            [capability](const Device &device) {
                return device.fd >= 0 && device.*capability;
            });
}

bool has_complete_capture(const std::vector<Device> &devices) {
    return has_capability(devices, &Device::keyboard) &&
           has_capability(devices, &Device::mouse_motion) &&
           has_capability(devices, &Device::mouse_buttons);
}

int incomplete_capture_error(const std::vector<Device> &devices) {
    if (devices.empty()) return kErrorNoMatchedNodes;
    if (!has_capability(devices, &Device::keyboard)) return kErrorNoKeyboardNode;
    if (!has_capability(devices, &Device::mouse_motion)) return kErrorNoMouseMotionNode;
    if (!has_capability(devices, &Device::mouse_buttons)) return kErrorNoMouseButtonNode;
    return ENODEV;
}

void close_device(Device &device) {
    if (device.fd < 0) return;
    ioctl(device.fd, EVIOCGRAB, 0);
    close(device.fd);
    device.fd = -1;
}

std::vector<Device> open_safe_devices(
        const std::vector<AllowedDevice> &allowed_devices,
        int &failure_errno) {
    std::vector<Device> devices;
    failure_errno = ENODEV;
    if (allowed_devices.empty()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "No external input devices were allowed");
        return devices;
    }
    DIR *directory = opendir("/dev/input");
    if (directory == nullptr) {
        failure_errno = errno;
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot open /dev/input: %s", strerror(errno));
        return devices;
    }

    while (dirent *entry = readdir(directory)) {
        if (strncmp(entry->d_name, "event", 5) != 0) continue;

        Device device;
        device.path = std::string("/dev/input/") + entry->d_name;
        device.fd = open(device.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (device.fd < 0) {
            if (errno == EACCES || errno == EPERM) failure_errno = errno;
            continue;
        }

        std::array<char, 256> name{};
        if (ioctl(device.fd, EVIOCGNAME(name.size()), name.data()) >= 0) {
            device.name = name.data();
        }
        const bool explicitly_allowed =
                is_allowed_device(device.fd, device.name, allowed_devices);

        if (!classify_device(
                    device.fd,
                    device.keyboard,
                    device.mouse_motion,
                    device.mouse_buttons,
                    device.mouse_scroll,
                    device.direct_touch)) {
            close(device.fd);
            continue;
        }

        if (!explicitly_allowed) {
            // Android sometimes treats a pogo-pin keyboard as internal or
            // gives its logical InputDevice a name different from EVIOCGNAME.
            // A virtual-bus fallback is accepted only for known Xiaomi pogo
            // keyboard bridges and only when the node exposes the complete
            // WASD cluster. Generic uinput_nav remains excluded here.
            const bool virtual_device = is_virtual_input_device(device.fd);
            const bool trusted_private_keyboard_bridge =
                    virtual_device && has_xiaomi_private_keyboard_name(device.name);
            const bool safe_keyboard_fallback =
                    device.keyboard &&
                    !device.direct_touch &&
                    !device.name.empty() &&
                    (!virtual_device || trusted_private_keyboard_bridge);
            if (!safe_keyboard_fallback) {
                close(device.fd);
                continue;
            }
            __android_log_print(
                    ANDROID_LOG_INFO,
                    kLogTag,
                    "Using full-keyboard fallback for %s (%s)",
                    device.path.c_str(),
                    device.name.c_str());
        }

        devices.push_back(std::move(device));
    }
    closedir(directory);

    // Root mode is only valid when native capture can take over both halves of
    // the user's keyboard-and-mouse setup.
    if (!has_complete_capture(devices)) {
        if (failure_errno != EACCES && failure_errno != EPERM && failure_errno != EBUSY) {
            failure_errno = incomplete_capture_error(devices);
        }
        __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "Native capture needs both a keyboard and a mouse");
        for (auto &device : devices) close_device(device);
        devices.clear();
    }
    return devices;
}

int held_button_count(const std::vector<Device> &devices) {
    int count = 0;
    for (const auto &device : devices) {
        if (!device.keyboard && !device.mouse_buttons) continue;
        BitArray<KEY_MAX> pressed{};
        if (ioctl(device.fd, EVIOCGKEY(sizeof(pressed)), pressed.data()) < 0) return -errno;
        for (const auto word : pressed) count += __builtin_popcountl(word);
    }
    return count;
}

// Android must receive both edges of keys pressed before capture. Grabbing
// between DOWN and UP leaves InputReader repeating the key indefinitely.
bool grab_released_devices(std::vector<Device> &devices, int &failure_errno) {
    bool reported_wait = false;
    while (g_running.load() && g_capture_enabled.load()) {
        const int held = held_button_count(devices);
        if (held < 0) {
            failure_errno = -held;
            return false;
        }
        if (held > 0) {
            if (!reported_wait) {
                __android_log_print(ANDROID_LOG_INFO, kLogTag,
                                    "Waiting for keyboard and mouse buttons to be released before capture");
                reported_wait = true;
            }
            usleep(10000);
            continue;
        }

        // Discard events already delivered to Android while waiting. They do
        // not belong to the new mapping session.
        for (auto &device : devices) {
            input_event pending[32];
            ssize_t bytes;
            do {
                bytes = read(device.fd, pending, sizeof(pending));
            } while (bytes > 0 || (bytes < 0 && errno == EINTR));
            if (bytes == 0 || (errno != EAGAIN && errno != EWOULDBLOCK)) {
                failure_errno = bytes == 0 ? ENODEV : errno;
                return false;
            }
        }
        for (auto &device : devices) {
            if (ioctl(device.fd, EVIOCGRAB, 1) < 0) {
                failure_errno = errno;
                __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                    "Cannot exclusively grab %s (%s): %s",
                                    device.path.c_str(), device.name.c_str(), strerror(errno));
                return false;
            }
        }

        // A press during the hand-off must also finish through Android.
        const int after_grab = held_button_count(devices);
        if (after_grab < 0) {
            failure_errno = -after_grab;
            return false;
        }
        if (after_grab == 0) {
            for (const auto &device : devices) {
                __android_log_print(ANDROID_LOG_INFO, kLogTag,
                                    "Captured %s (%s), keyboard=%d mouseMotion=%d mouseButtons=%d mouseScroll=%d",
                                    device.path.c_str(), device.name.c_str(), device.keyboard,
                                    device.mouse_motion, device.mouse_buttons, device.mouse_scroll);
            }
            return true;
        }
        for (auto &device : devices) ioctl(device.fd, EVIOCGRAB, 0);
    }
    failure_errno = ECANCELED;
    return false;
}

bool call_void(JNIEnv *env, jobject callback, jmethodID method, jint first, jint second) {
    env->CallVoidMethod(callback, method, first, second);
    if (!env->ExceptionCheck()) return true;
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Java input callback failed");
    return false;
}

bool call_void(JNIEnv *env, jobject callback, jmethodID method, jint first) {
    env->CallVoidMethod(callback, method, first);
    if (!env->ExceptionCheck()) return true;
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Java input callback failed");
    return false;
}

bool call_void(JNIEnv *env, jobject callback, jmethodID method) {
    env->CallVoidMethod(callback, method);
    if (!env->ExceptionCheck()) return true;
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Java input callback failed");
    return false;
}

std::vector<AllowedDevice> read_allowed_devices(
        JNIEnv *env,
        jobjectArray names,
        jintArray vendor_ids,
        jintArray product_ids) {
    if (names == nullptr || vendor_ids == nullptr || product_ids == nullptr) return {};
    const jsize count = env->GetArrayLength(names);
    if (count <= 0 || env->GetArrayLength(vendor_ids) != count ||
        env->GetArrayLength(product_ids) != count) {
        return {};
    }

    std::vector<jint> vendors(static_cast<size_t>(count));
    std::vector<jint> products(static_cast<size_t>(count));
    env->GetIntArrayRegion(vendor_ids, 0, count, vendors.data());
    env->GetIntArrayRegion(product_ids, 0, count, products.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {};
    }

    std::vector<AllowedDevice> devices;
    devices.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto name = static_cast<jstring>(env->GetObjectArrayElement(names, index));
        if (name == nullptr) return {};
        const char *utf_name = env->GetStringUTFChars(name, nullptr);
        if (utf_name == nullptr) {
            env->DeleteLocalRef(name);
            if (env->ExceptionCheck()) env->ExceptionClear();
            return {};
        }
        devices.push_back(AllowedDevice{
                utf_name,
                static_cast<int>(vendors[static_cast<size_t>(index)]),
                static_cast<int>(products[static_cast<size_t>(index)])});
        env->ReleaseStringUTFChars(name, utf_name);
        env->DeleteLocalRef(name);
    }
    return devices;
}

bool flush_mouse_motion(
        JNIEnv *env,
        jobject callback,
        jmethodID on_mouse_move,
        Device &device) {
    if (device.relative_x == 0 && device.relative_y == 0) return true;
    const int relative_x = device.relative_x;
    const int relative_y = device.relative_y;
    device.relative_x = 0;
    device.relative_y = 0;
    return call_void(env, callback, on_mouse_move, relative_x, relative_y);
}

bool flush_all_mouse_motion(
        JNIEnv *env,
        jobject callback,
        jmethodID on_mouse_move,
        std::vector<Device> &devices) {
    for (auto &device : devices) {
        if (device.fd >= 0 && device.mouse_motion &&
            !flush_mouse_motion(env, callback, on_mouse_move, device)) {
            return false;
        }
    }
    return true;
}

bool flush_mouse_scroll(
        JNIEnv *env,
        jobject callback,
        jmethodID on_mouse_scroll,
        Device &device) {
    int vertical_units = 0;
    if (device.relative_wheel != 0) {
        // Linux normally emits REL_WHEEL together with REL_WHEEL_HI_RES for
        // the same physical movement. Prefer the legacy detent in that case
        // so a single wheel click is never delivered twice.
        vertical_units = device.relative_wheel * kWheelUnitsPerNotch;
        device.relative_wheel = 0;
        device.relative_wheel_hi_res = 0;
    } else {
        // A number of touchpads, including vendor keyboard-cover devices,
        // expose only high-resolution wheel deltas. Preserve sub-detent
        // values across SYN_REPORT frames until they form a usable notch.
        const int complete_notches =
                device.relative_wheel_hi_res / kWheelUnitsPerNotch;
        if (complete_notches == 0) return true;
        vertical_units = complete_notches * kWheelUnitsPerNotch;
        device.relative_wheel_hi_res -= vertical_units;
    }
    return call_void(env, callback, on_mouse_scroll, vertical_units);
}

bool flush_all_mouse_scroll(
        JNIEnv *env,
        jobject callback,
        jmethodID on_mouse_scroll,
        std::vector<Device> &devices) {
    for (auto &device : devices) {
        if (device.fd >= 0 && device.mouse_scroll &&
            !flush_mouse_scroll(env, callback, on_mouse_scroll, device)) {
            return false;
        }
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_devoid_keysync_data_external_RootEvdevNative_nativeRunCapture(
        JNIEnv *env,
        jobject,
        jobject callback,
        jobjectArray allowed_names,
        jintArray allowed_vendor_ids,
        jintArray allowed_product_ids) {
    if (!g_capture_enabled.load()) return -ECANCELED;
    bool expected = false;
    if (!g_running.compare_exchange_strong(expected, true)) return -EBUSY;

    jclass callback_class = env->GetObjectClass(callback);
    const jmethodID on_key = env->GetMethodID(callback_class, "onNativeKey", "(II)V");
    const jmethodID on_mouse_button =
            env->GetMethodID(callback_class, "onNativeMouseButton", "(II)V");
    const jmethodID on_mouse_move =
            env->GetMethodID(callback_class, "onNativeMouseMove", "(II)V");
    const jmethodID on_mouse_scroll =
            env->GetMethodID(callback_class, "onNativeMouseScroll", "(I)V");
    const jmethodID on_reset = env->GetMethodID(callback_class, "onNativeInputReset", "()V");
    const jmethodID on_state = env->GetMethodID(callback_class, "onNativeCaptureState", "(I)V");
    env->DeleteLocalRef(callback_class);
    if (on_key == nullptr || on_mouse_button == nullptr || on_mouse_move == nullptr ||
        on_mouse_scroll == nullptr || on_reset == nullptr || on_state == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        g_running.store(false);
        return -EINVAL;
    }

    const std::vector<AllowedDevice> allowed_devices = read_allowed_devices(
            env,
            allowed_names,
            allowed_vendor_ids,
            allowed_product_ids);
    int open_failure_errno = ENODEV;
    std::vector<Device> devices = open_safe_devices(allowed_devices, open_failure_errno);
    if (devices.empty() || !grab_released_devices(devices, open_failure_errno)) {
        for (auto &device : devices) close_device(device);
        call_void(env, callback, on_state, 0);
        g_running.store(false);
        return -open_failure_errno;
    }

    const int epoll_fd = epoll_create1(EPOLL_CLOEXEC);
    const int wake_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (epoll_fd < 0 || wake_fd < 0) {
        const int saved_errno = errno;
        for (auto &device : devices) close_device(device);
        if (epoll_fd >= 0) close(epoll_fd);
        if (wake_fd >= 0) close(wake_fd);
        g_running.store(false);
        return -saved_errno;
    }

    epoll_event wake_event{};
    wake_event.events = EPOLLIN;
    wake_event.data.fd = kWakeMarker;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, wake_fd, &wake_event) < 0) {
        const int saved_errno = errno;
        for (auto &device : devices) close_device(device);
        close(wake_fd);
        close(epoll_fd);
        g_running.store(false);
        return -saved_errno;
    }
    for (auto &device : devices) {
        epoll_event input_event{};
        input_event.events = EPOLLIN | EPOLLERR | EPOLLHUP;
        input_event.data.fd = device.fd;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, device.fd, &input_event) < 0) {
            __android_log_print(
                    ANDROID_LOG_WARN,
                    kLogTag,
                    "Cannot monitor %s (%s): %s",
                    device.path.c_str(),
                    device.name.c_str(),
                    strerror(errno));
            close_device(device);
        }
    }
    if (!has_complete_capture(devices)) {
        for (auto &device : devices) close_device(device);
        close(wake_fd);
        close(epoll_fd);
        call_void(env, callback, on_state, 0);
        g_running.store(false);
        return -ENODEV;
    }

    g_wake_fd.store(wake_fd);
    const auto active_device_count = std::count_if(
            devices.begin(),
            devices.end(),
            [](const Device &device) { return device.fd >= 0; });
    call_void(env, callback, on_state, static_cast<jint>(active_device_count));
    std::array<epoll_event, kMaxEpollEvents> events{};

    bool capture_incomplete = false;
    while (g_running.load() && g_capture_enabled.load()) {
        const int ready = epoll_wait(epoll_fd, events.data(), events.size(), -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            capture_incomplete = true;
            break;
        }

        for (int index = 0; index < ready && g_running.load(); ++index) {
            const int ready_fd = events[index].data.fd;
            if (ready_fd == kWakeMarker) {
                eventfd_t value;
                eventfd_read(wake_fd, &value);
                continue;
            }

            auto device = std::find_if(
                    devices.begin(),
                    devices.end(),
                    [ready_fd](const Device &candidate) { return candidate.fd == ready_fd; });
            if (device == devices.end() || device->fd < 0) continue;

            if ((events[index].events & (EPOLLERR | EPOLLHUP)) != 0) {
                call_void(env, callback, on_reset);
                epoll_ctl(epoll_fd, EPOLL_CTL_DEL, device->fd, nullptr);
                close_device(*device);
                capture_incomplete = !has_complete_capture(devices);
                if (capture_incomplete) g_running.store(false);
                continue;
            }

            input_event event{};
            ssize_t bytes_read = 0;
            while ((bytes_read = read(device->fd, &event, sizeof(event))) == sizeof(event)) {
                if (event.type == EV_SYN && event.code == SYN_DROPPED) {
                    device->relative_x = 0;
                    device->relative_y = 0;
                    device->relative_wheel = 0;
                    device->relative_wheel_hi_res = 0;
                    device->dropping_events = true;
                    call_void(env, callback, on_reset);
                    continue;
                }
                if (device->dropping_events) {
                    if (event.type == EV_SYN && event.code == SYN_REPORT) {
                        device->dropping_events = false;
                    }
                    continue;
                }
                if (event.type == EV_REL && device->mouse_motion) {
                    if (event.code == REL_X) device->relative_x += event.value;
                    if (event.code == REL_Y) device->relative_y += event.value;
                    if (event.code == REL_X || event.code == REL_Y) continue;
                }
                if (event.type == EV_REL && device->mouse_scroll) {
                    if (event.code == REL_WHEEL) {
                        device->relative_wheel += event.value;
                        continue;
                    }
                    if (event.code == REL_WHEEL_HI_RES) {
                        device->relative_wheel_hi_res += event.value;
                        continue;
                    }
                }
                if (event.type == EV_KEY && (event.value == 0 || event.value == 1)) {
                    if (device->mouse_buttons &&
                        (event.code == BTN_LEFT || event.code == BTN_RIGHT ||
                         event.code == BTN_MIDDLE)) {
                        if (!flush_all_mouse_motion(
                                    env,
                                    callback,
                                    on_mouse_move,
                                    devices)) {
                            g_running.store(false);
                            break;
                        }
                        if (!flush_all_mouse_scroll(
                                    env,
                                    callback,
                                    on_mouse_scroll,
                                    devices)) {
                            g_running.store(false);
                            break;
                        }
                        call_void(env, callback, on_mouse_button, event.code, event.value);
                    } else if (device->keyboard && event.code < BTN_MISC) {
                        call_void(env, callback, on_key, event.code, event.value);
                    }
                    continue;
                }
                if (event.type == EV_SYN && event.code == SYN_REPORT) {
                    if (device->mouse_motion &&
                        !flush_mouse_motion(env, callback, on_mouse_move, *device)) {
                        g_running.store(false);
                        break;
                    }
                    if (device->mouse_scroll &&
                        !flush_mouse_scroll(env, callback, on_mouse_scroll, *device)) {
                        g_running.store(false);
                        break;
                    }
                }
            }
            const bool read_failed =
                    bytes_read == 0 ||
                    (bytes_read < 0 && errno != EAGAIN && errno != EWOULDBLOCK) ||
                    (bytes_read > 0 && bytes_read != static_cast<ssize_t>(sizeof(event)));
            if (read_failed) {
                call_void(env, callback, on_reset);
                epoll_ctl(epoll_fd, EPOLL_CTL_DEL, device->fd, nullptr);
                close_device(*device);
                capture_incomplete = !has_complete_capture(devices);
                if (capture_incomplete) g_running.store(false);
            }
        }
    }

    g_wake_fd.store(-1);
    call_void(env, callback, on_state, 0);
    for (auto &device : devices) close_device(device);
    close(wake_fd);
    close(epoll_fd);
    g_running.store(false);
    return capture_incomplete ? -ENODEV : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_devoid_keysync_data_external_RootEvdevNative_nativePrepareCapture(
        JNIEnv *,
        jobject) {
    g_capture_enabled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_devoid_keysync_data_external_RootEvdevNative_nativeStopCapture(
        JNIEnv *,
        jobject) {
    g_capture_enabled.store(false);
    g_running.store(false);
    const int wake_fd = g_wake_fd.load();
    if (wake_fd >= 0) {
        eventfd_t value = 1;
        eventfd_write(wake_fd, value);
    }
}
