<div align="center">
<img src=".github/resources/icon.png" height="180px" width="auto" alt="romm logo">
</div>

# AYASpaceXML
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Language](https://img.shields.io/badge/language-Kotlin-purple.svg)

Sync ES-DE `gamelist.xml` files and media assets (`image`, `thumbnail`) to enable full AYASpace compatibility.

### Directory Structure

**Source (ES-DE):**
```
ES-DE/
├── gamelists/
│   └── n3ds/
│       └── gamelist.xml
└── downloaded_media/
    └── n3ds/
        ├── covers/
        ├── fanart/
        └── screenshots/
```

**Destination (ROMs for AYASpace):**
```
ROMs/
└── n3ds/
    ├── gamelist.xml
    └── media/
        ├── image/        (fanart or screenshots)
        └── thumbnail/    (covers)
```

### Media Processing Logic

| Source | Destination | Fallback |
|--------|-------------|----------|
| **Covers** → `thumbnail/` | Box art for game lists | None |
| **Fanart** → `image/` | Full-screen artwork | Falls back to screenshots if no fanart |
| **Screenshots** → `image/` | Used only if no fanart exists | None |

### Sync Behavior

The app performs an incremental sync instead of deleting and rebuilding all media every run:

- New source media is copied to the destination
- Existing destination media is kept when the filename and file size match
- Existing destination media is replaced when the source file size differs
- Destination media that is no longer referenced by the source gamelist/media selection is deleted
- `gamelist.xml` is regenerated with AYASpace-compatible relative media paths

This means repeat syncs with no source changes should be much faster and mostly skip work.

### App Features

- Source and destination folder pickers using Android's document tree access
- Portrait and landscape-aware layout
- Per-run progress bar with system-level progress and in-system action progress
- Sync summary dialog with top-level totals and expandable per-system details
- Optional system filtering:
  - Sync all systems
  - Choose individual systems from a checklist

### Usage

1. **Select ES-DE Folder**
   Choose the ES-DE folder containing `gamelists/` and `downloaded_media/`
2. **Select ROMs Folder**
   Choose the AYASpace ROMs folder that contains the destination system directories
3. **Choose Systems to Sync**
   Sync all discovered systems or switch to individual selection
4. **Press Sync**
   The app shows a determinate progress bar while syncing
5. **Review the Summary**
   When the sync completes, the app shows totals and optional per-system details

Each time you run the sync operation:
- New media is added
- Changed media is updated
- Removed media is deleted
- Unchanged media is skipped
- `gamelist.xml` is regenerated with correct paths
