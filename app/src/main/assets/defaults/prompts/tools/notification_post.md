Post a notification on the device.

Parameters:
- `title` — short title.
- `body` — notification text.

Use it to tell the user a long-running task has finished when they may have
switched apps. Do not use it for ordinary replies. On Android 13 and newer
the user must have granted the notification permission; if not, the tool
returns an error.
