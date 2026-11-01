#include <jni.h>

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <array>
#include <cstring>
#include <mutex>

namespace {

constexpr char kLogTag[] = "RootVirtualGamepad";
constexpr char kDeviceName[] = "KeySync Virtual Gamepad";
constexpr int kAxisMinimum = -32767;
constexpr int kAxisMaximum = 32767;
constexpr int kTriggerMaximum = 255;

std::mutex g_gamepad_mutex;
int g_gamepad_fd = -1;

constexpr std::array<unsigned short, 13> kButtons{
        BTN_SOUTH,
        BTN_EAST,
        BTN_NORTH,
        BTN_WEST,
        BTN_TL,
        BTN_TR,
        BTN_TL2,
        BTN_TR2,
        BTN_SELECT,
        BTN_START,
        BTN_MODE,
        BTN_THUMBL,
        BTN_THUMBR};

bool emit_event_locked(unsigned short type, unsigned short code, int value) {
    if (g_gamepad_fd < 0) return false;
    input_event event{};
    event.type = type;
    event.code = code;
    event.value = value;
    const ssize_t written = write(g_gamepad_fd, &event, sizeof(event));
    return written == static_cast<ssize_t>(sizeof(event));
}

bool sync_locked() {
    return emit_event_locked(EV_SYN, SYN_REPORT, 0);
}

bool configure_axis(int fd, unsigned short code, int minimum, int maximum) {
    if (ioctl(fd, UI_SET_ABSBIT, code) < 0) return false;
    uinput_abs_setup setup{};
    setup.code = code;
    setup.absinfo.minimum = minimum;
    setup.absinfo.maximum = maximum;
    setup.absinfo.fuzz = 0;
    setup.absinfo.flat = 0;
    setup.absinfo.resolution = 0;
    return ioctl(fd, UI_ABS_SETUP, &setup) >= 0;
}

bool reset_locked() {
    if (g_gamepad_fd < 0) return false;
    bool succeeded = true;
    for (const unsigned short button : kButtons) {
        succeeded = emit_event_locked(EV_KEY, button, 0) && succeeded;
    }
    for (const unsigned short axis : {ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y}) {
        succeeded = emit_event_locked(EV_ABS, axis, 0) && succeeded;
    }
    succeeded = emit_event_locked(EV_ABS, ABS_GAS, 0) && succeeded;
    succeeded = emit_event_locked(EV_ABS, ABS_BRAKE, 0) && succeeded;
    return sync_locked() && succeeded;
}

int open_uinput() {
    constexpr std::array<const char *, 2> paths{"/dev/uinput", "/dev/input/uinput"};
    int failure = ENOENT;
    for (const char *path : paths) {
        const int fd = open(path, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd >= 0) return fd;
        failure = errno;
    }
    errno = failure;
    return -1;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_devoid_keysync_data_external_RootVirtualGamepadNative_nativeCreate(
        JNIEnv *,
        jobject) {
    std::lock_guard<std::mutex> lock(g_gamepad_mutex);
    if (g_gamepad_fd >= 0) return 0;

    const int fd = open_uinput();
    if (fd < 0) {
        const int failure = errno;
        __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Cannot open uinput: %s",
                strerror(failure));
        return -failure;
    }

    bool configured = ioctl(fd, UI_SET_EVBIT, EV_KEY) >= 0;
    for (const unsigned short button : kButtons) {
        configured = ioctl(fd, UI_SET_KEYBIT, button) >= 0 && configured;
    }
    configured = ioctl(fd, UI_SET_EVBIT, EV_ABS) >= 0 && configured;
    configured = configure_axis(fd, ABS_X, kAxisMinimum, kAxisMaximum) && configured;
    configured = configure_axis(fd, ABS_Y, kAxisMinimum, kAxisMaximum) && configured;
    // Generic.kl maps raw ABS_Z and ABS_RZ to Android AXIS_Z/AXIS_RZ,
    // which are the standard Android right-stick axes.
    configured = configure_axis(fd, ABS_Z, kAxisMinimum, kAxisMaximum) && configured;
    configured = configure_axis(fd, ABS_RZ, kAxisMinimum, kAxisMaximum) && configured;
    configured = configure_axis(fd, ABS_GAS, 0, kTriggerMaximum) && configured;
    configured = configure_axis(fd, ABS_BRAKE, 0, kTriggerMaximum) && configured;
    configured = configure_axis(fd, ABS_HAT0X, -1, 1) && configured;
    configured = configure_axis(fd, ABS_HAT0Y, -1, 1) && configured;

    uinput_setup setup{};
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0;
    setup.id.product = 0;
    setup.id.version = 1;
    strncpy(setup.name, kDeviceName, UINPUT_MAX_NAME_SIZE - 1);
    configured = ioctl(fd, UI_DEV_SETUP, &setup) >= 0 && configured;
    configured = ioctl(fd, UI_DEV_CREATE) >= 0 && configured;
    if (!configured) {
        const int failure = errno == 0 ? EIO : errno;
        __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Cannot configure uinput gamepad: %s",
                strerror(failure));
        close(fd);
        return -failure;
    }

    g_gamepad_fd = fd;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Created %s", kDeviceName);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_devoid_keysync_data_external_RootVirtualGamepadNative_nativeDestroy(
        JNIEnv *,
        jobject) {
    std::lock_guard<std::mutex> lock(g_gamepad_mutex);
    if (g_gamepad_fd < 0) return;
    reset_locked();
    ioctl(g_gamepad_fd, UI_DEV_DESTROY);
    close(g_gamepad_fd);
    g_gamepad_fd = -1;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Destroyed virtual gamepad");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_devoid_keysync_data_external_RootVirtualGamepadNative_nativeSetLeftStick(
        JNIEnv *,
        jobject,
        jint x,
        jint y) {
    std::lock_guard<std::mutex> lock(g_gamepad_mutex);
    const bool succeeded =
            emit_event_locked(EV_ABS, ABS_X, x) &&
            emit_event_locked(EV_ABS, ABS_Y, y) &&
            sync_locked();
    return succeeded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_devoid_keysync_data_external_RootVirtualGamepadNative_nativeSetRightStick(
        JNIEnv *,
        jobject,
        jint x,
        jint y) {
    std::lock_guard<std::mutex> lock(g_gamepad_mutex);
    const bool succeeded =
            emit_event_locked(EV_ABS, ABS_Z, x) &&
            emit_event_locked(EV_ABS, ABS_RZ, y) &&
            sync_locked();
    return succeeded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_devoid_keysync_data_external_RootVirtualGamepadNative_nativeSetButton(
        JNIEnv *,
        jobject,
        jint linux_code,
        jboolean pressed) {
    std::lock_guard<std::mutex> lock(g_gamepad_mutex);
    const bool succeeded =
            emit_event_locked(EV_KEY, static_cast<unsigned short>(linux_code), pressed ? 1 : 0) &&
            sync_locked();
    return succeeded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_devoid_keysync_data_external_RootVirtualGamepadNative_nativeReset(
        JNIEnv *,
        jobject) {
    std::lock_guard<std::mutex> lock(g_gamepad_mutex);
    return reset_locked() ? JNI_TRUE : JNI_FALSE;
}
