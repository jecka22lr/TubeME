Code review fixes. Downloads and playback are still unconfirmed on a real phone.

- H.264 360p is hidden on S60 3rd and 5th Edition, where it plays as sound only
- Downloads: no repeated server rounds, cut-off downloads are reported as failed, error pages are not saved as videos, an earlier complete file is never deleted by a failed retry
- Every request has a time limit
- The crash log keeps rolling in long sessions
- A failed server-list update is retried on the next start
