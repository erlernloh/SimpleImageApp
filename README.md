# Photara - Professional Photo Editor

A comprehensive Android photo editing application built with Jetpack Compose, offering professional-grade photo editing capabilities with real-time preview and creative effects.

> **Note**: Internally uses package `com.imagedit.app` for code organization, while the user-facing app name is **Photara**.

## Features

- **Camera Integration**: Capture photos with CameraX with full manual controls
- **Professional Editing**: Brightness, contrast, saturation, exposure, temperature, tint, and more
- **Color Controls**: Comprehensive color adjustments with real-time preview
- **Creative Effects**: Multiple filters, vignette, film grain, blur effects
- **Transform Tools**: Crop with aspect ratios, rotate, flip horizontal/vertical
- **Preset System**: Save, edit, and apply custom editing presets
- **Gallery Management**: View, sort, and organize your photos with favorites
- **Export Options**: Multiple formats (JPEG, PNG, WebP) with quality control and resize options
- **Undo/Redo**: Full edit history with unlimited undo/redo support

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose with Material 3
- **Architecture**: MVVM with Clean Architecture
- **Dependency Injection**: Hilt
- **Camera**: CameraX with Camera2 interop
- **Image Processing**: Android Graphics API (Canvas, Matrix, ColorMatrix)
- **Image Loading**: Coil with memory and disk caching
- **Data Storage**: MediaStore for photos, DataStore for preferences
- **Async**: Kotlin Coroutines + Flow with background threading

## Requirements

- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 24+ (Android 7.0+)
- Physical device recommended for camera testing

## Getting Started

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle files
4. Run on device or emulator

## Project Structure

```
app/src/main/java/com/imagedit/app/
├── data/           # Data layer (repositories, local storage)
├── domain/         # Domain layer (models, use cases)
├── di/             # Dependency injection modules
├── ui/             # UI layer (screens, components, theme)
└── util/           # Utilities and extensions
```

## Documentation

- [Implementation Task List](IMPLEMENTATION_TASK_LIST.md) - Detailed development roadmap
- [Quick Start Guide](QUICK_START_GUIDE.md) - Setup and first steps
- [Reference Mapping](REFERENCE_MAPPING.md) - Code reuse guide

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]
