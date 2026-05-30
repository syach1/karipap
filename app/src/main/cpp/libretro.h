#ifndef LIBRETRO_H__
#define LIBRETRO_H__

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RETRO_API_VERSION 1

// Pixel formats
#define RETRO_PIXEL_FORMAT_0RGB1555 0
#define RETRO_PIXEL_FORMAT_XRGB8888 1
#define RETRO_PIXEL_FORMAT_RGB565   2

// Audio/video enable flags
#define RETRO_AV_ENABLE_VIDEO 1
#define RETRO_AV_ENABLE_AUDIO 2
#define RETRO_AV_ENABLE_FAST_SAVESTATES 4
#define RETRO_AV_ENABLE_HARD_DISABLE_AUDIO 8

// Device types
#define RETRO_DEVICE_NONE    0
#define RETRO_DEVICE_JOYPAD  1
#define RETRO_DEVICE_ANALOG  5

// Analog stick indices
#define RETRO_DEVICE_INDEX_ANALOG_LEFT   0
#define RETRO_DEVICE_INDEX_ANALOG_RIGHT  1

// Analog axis IDs
#define RETRO_DEVICE_ID_ANALOG_X  0
#define RETRO_DEVICE_ID_ANALOG_Y  1

// Joypad buttons
#define RETRO_DEVICE_ID_JOYPAD_B      0
#define RETRO_DEVICE_ID_JOYPAD_Y      1
#define RETRO_DEVICE_ID_JOYPAD_SELECT 2
#define RETRO_DEVICE_ID_JOYPAD_START  3
#define RETRO_DEVICE_ID_JOYPAD_UP     4
#define RETRO_DEVICE_ID_JOYPAD_DOWN   5
#define RETRO_DEVICE_ID_JOYPAD_LEFT   6
#define RETRO_DEVICE_ID_JOYPAD_RIGHT  7
#define RETRO_DEVICE_ID_JOYPAD_A      8
#define RETRO_DEVICE_ID_JOYPAD_X      9
#define RETRO_DEVICE_ID_JOYPAD_L     10
#define RETRO_DEVICE_ID_JOYPAD_R     11
#define RETRO_DEVICE_ID_JOYPAD_L2    12
#define RETRO_DEVICE_ID_JOYPAD_R2    13
#define RETRO_DEVICE_ID_JOYPAD_L3    14
#define RETRO_DEVICE_ID_JOYPAD_R3    15

// Memory types
#define RETRO_MEMORY_SAVE_RAM 0
#define RETRO_MEMORY_RTC      1

// Environment callback commands
#define RETRO_ENVIRONMENT_GET_OVERSCAN           2
#define RETRO_ENVIRONMENT_GET_CAN_DUPE           3
#define RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL  8
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT       10
#define RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS  11
#define RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE     13
#define RETRO_ENVIRONMENT_GET_VARIABLE           15
#define RETRO_ENVIRONMENT_SET_VARIABLES          16
#define RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE    17
#define RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME    18
#define RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK 21
#define RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK     22
#define RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE   23
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE      27
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY     31
#define RETRO_ENVIRONMENT_SET_CONTROLLER_INFO    35
#define RETRO_ENVIRONMENT_SET_MEMORY_MAPS        36

#define RETRO_DEVICE_ID_JOYPAD_MASK  256
#define RETRO_ENVIRONMENT_SET_GEOMETRY           37
#define RETRO_ENVIRONMENT_GET_LANGUAGE           39
#define RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS 44
#define RETRO_ENVIRONMENT_GET_VFS_INTERFACE      45
#define RETRO_ENVIRONMENT_GET_LED_INTERFACE      46
#define RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE 47
#define RETRO_ENVIRONMENT_GET_FASTFORWARDING     49
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY   9
#define RETRO_ENVIRONMENT_GET_INPUT_BITMASKS     51
#define RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION 52
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS           53
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL      54
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY   55
#define RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION 57
#define RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE_CURRENT 58
#define RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS    61
#define RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK 62
#define RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY 63
#define RETRO_ENVIRONMENT_GET_GAME_INFO_EXT      66
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2        67
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL  68
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK 69
#define RETRO_ENVIRONMENT_SET_VARIABLE           70
#define RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE 65

// Log levels
enum retro_log_level {
    RETRO_LOG_DEBUG = 0,
    RETRO_LOG_INFO,
    RETRO_LOG_WARN,
    RETRO_LOG_ERROR
};

typedef void (*retro_log_printf_t)(enum retro_log_level level, const char *fmt, ...);

struct retro_log_callback {
    retro_log_printf_t log;
};

struct retro_variable {
    const char *key;
    const char *value;
};

struct retro_core_option_value {
    const char *value;
    const char *label;
};

struct retro_core_option_definition {
    const char *key;
    const char *desc;
    const char *info;
    struct retro_core_option_value values[128];
    const char *default_value;
};

struct retro_core_options_intl {
    struct retro_core_option_definition *us;
    struct retro_core_option_definition *local;
};

struct retro_core_option_v2_definition {
    const char *key;
    const char *desc;
    const char *desc_categorized;
    const char *info;
    const char *info_categorized;
    const char *category_key;
    struct retro_core_option_value values[128];
    const char *default_value;
};

struct retro_core_option_v2_category {
    const char *key;
    const char *desc;
    const char *info;
};

struct retro_core_options_v2 {
    struct retro_core_option_v2_category *categories;
    struct retro_core_option_v2_definition *definitions;
};

struct retro_core_options_v2_intl {
    struct retro_core_options_v2 *us;
    struct retro_core_options_v2 *local;
};

struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
};

struct retro_system_timing {
    double fps;
    double sample_rate;
};

struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};

struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
};

struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

struct retro_game_info_ext {
    const char *full_path;
    const char *archive_path;
    const char *archive_file;
    const char *dir;
    const char *name;
    const char *ext;
    const char *meta;
    const void *data;
    size_t size;
    bool file_in_archive;
    bool persistent_data;
};

// Controller info
struct retro_controller_description {
    const char *desc;
    unsigned id;
};

struct retro_controller_info {
    const struct retro_controller_description *types;
    unsigned num_types;
};

// Callback typedefs
typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

typedef int64_t retro_usec_t;
typedef void (*retro_frame_time_callback_t)(retro_usec_t usec);

struct retro_frame_time_callback {
    retro_frame_time_callback_t callback;
    retro_usec_t reference;
};

// Core API function typedefs
typedef void (*retro_set_environment_t)(retro_environment_t);
typedef void (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void (*retro_set_input_state_t)(retro_input_state_t);
typedef void (*retro_init_t)(void);
typedef void (*retro_deinit_t)(void);
typedef unsigned (*retro_api_version_t)(void);
typedef void (*retro_get_system_info_t)(struct retro_system_info *info);
typedef void (*retro_get_system_av_info_t)(struct retro_system_av_info *info);
typedef void (*retro_set_controller_port_device_t)(unsigned port, unsigned device);
typedef void (*retro_reset_t)(void);
typedef void (*retro_run_t)(void);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool (*retro_serialize_t)(void *data, size_t size);
typedef bool (*retro_unserialize_t)(const void *data, size_t size);
typedef bool (*retro_load_game_t)(const struct retro_game_info *game);
typedef void (*retro_unload_game_t)(void);
typedef void *(*retro_get_memory_data_t)(unsigned id);
typedef size_t (*retro_get_memory_size_t)(unsigned id);

// Disk control interface
struct retro_disk_control_callback {
    bool (*set_eject_state)(bool ejected);
    bool (*get_eject_state)(void);
    unsigned (*get_image_index)(void);
    bool (*set_image_index)(unsigned index);
    unsigned (*get_num_images)(void);
    bool (*replace_image_index)(unsigned index, const struct retro_game_info *info);
    bool (*add_image_index)(void);
};

struct retro_disk_control_ext_callback {
    bool (*set_eject_state)(bool ejected);
    bool (*get_eject_state)(void);
    unsigned (*get_image_index)(void);
    bool (*set_image_index)(unsigned index);
    unsigned (*get_num_images)(void);
    bool (*replace_image_index)(unsigned index, const struct retro_game_info *info);
    bool (*add_image_index)(void);
    bool (*set_initial_image)(unsigned index, const char *path);
    const char *(*get_image_path)(unsigned index);
    const char *(*get_image_label)(unsigned index);
};

// VFS interface
struct retro_vfs_file_handle;
struct retro_vfs_dir_handle;

#define RETRO_VFS_FILE_ACCESS_READ            (1 << 0)
#define RETRO_VFS_FILE_ACCESS_WRITE           (1 << 1)
#define RETRO_VFS_FILE_ACCESS_READ_WRITE      (RETRO_VFS_FILE_ACCESS_READ | RETRO_VFS_FILE_ACCESS_WRITE)
#define RETRO_VFS_FILE_ACCESS_UPDATE_EXISTING (1 << 2)

#define RETRO_VFS_SEEK_POSITION_START    0
#define RETRO_VFS_SEEK_POSITION_CURRENT  1
#define RETRO_VFS_SEEK_POSITION_END      2

#define RETRO_VFS_STAT_IS_VALID             (1 << 0)
#define RETRO_VFS_STAT_IS_DIRECTORY         (1 << 1)
#define RETRO_VFS_STAT_IS_CHARACTER_SPECIAL (1 << 2)

struct retro_vfs_interface {
    const char *(*get_path)(struct retro_vfs_file_handle *stream);
    struct retro_vfs_file_handle *(*open)(const char *path, unsigned mode, unsigned hints);
    int (*close)(struct retro_vfs_file_handle *stream);
    int64_t (*size)(struct retro_vfs_file_handle *stream);
    int64_t (*tell)(struct retro_vfs_file_handle *stream);
    int64_t (*seek)(struct retro_vfs_file_handle *stream, int64_t offset, int seek_position);
    int64_t (*read)(struct retro_vfs_file_handle *stream, void *s, uint64_t len);
    int64_t (*write)(struct retro_vfs_file_handle *stream, const void *s, uint64_t len);
    int (*flush)(struct retro_vfs_file_handle *stream);
    int (*remove)(const char *path);
    int (*rename)(const char *old_path, const char *new_path);
    int64_t (*truncate)(struct retro_vfs_file_handle *stream, int64_t length);
    int (*stat)(const char *path, int32_t *size);
    int (*mkdir)(const char *dir);
    struct retro_vfs_dir_handle *(*opendir)(const char *dir, bool include_hidden);
    bool (*readdir)(struct retro_vfs_dir_handle *dirstream);
    const char *(*dirent_get_name)(struct retro_vfs_dir_handle *dirstream);
    bool (*dirent_is_dir)(struct retro_vfs_dir_handle *dirstream);
    int (*closedir)(struct retro_vfs_dir_handle *dirstream);
    int (*stat_64)(const char *path, int64_t *size);
};

struct retro_vfs_interface_info {
    uint32_t required_interface_version;
    struct retro_vfs_interface *iface;
};

// Memory types
#define RETRO_MEMORY_SYSTEM_RAM  2
#define RETRO_MEMORY_VIDEO_RAM   3

// Memory descriptor for rc_libretro
struct retro_memory_descriptor {
    uint64_t flags;
    void *ptr;
    size_t offset;
    size_t start;
    size_t select;
    size_t disconnect;
    size_t len;
    const char *addrspace;
};

struct retro_memory_map {
    const struct retro_memory_descriptor *descriptors;
    unsigned num_descriptors;
};

#ifdef __cplusplus
}
#endif

#endif
