# YouChat 💬

A modern Android chat application built entirely with **Jetpack Compose** to prove a point: you do not need to be a senior Android developer to build premium, production-level features. 

**I am not an experienced Android developer. In fact, this is one of my very first apps.** 

I built YouChat as an open-source reference project for other beginners. It demonstrates how incredibly accessible modern Android media APIs have become—specifically the **Embedded Photo Picker** (introduced in Android 14) and seamless video playback using **AndroidX Media3 (ExoPlayer)**. If I can figure this out and build a working chat interface, you absolutely can too.

---

## 🚀 What This App Does
On the surface, YouChat is a streamlined, single-screen messaging interface. Under the hood, it demonstrates how a beginner can handle complex media states, gesture-driven layouts, and seamless media playback within Jetpack Compose.

**Core Capabilities:**
* **Split-Screen Media Preview:** Select up to 10 photos/videos and navigate through them using a custom carousel while keeping the picker open.
* **Dynamic UI States:** The input bar smoothly swaps between text input mode and a keyboard-return toggle depending on the picker's state.
* **Video Playback:** Native, lifecycle-aware video streaming with custom tap-to-pause Compose UI overlays using Media3.
* **Advanced Gestures:** Custom nested scrolling that allows a user to swipe up to expand the photo grid, and swipe down to collapse it.

---

## 📸 Spotlight: The Embedded Photo Picker
Historically, requesting photos in Android required writing a lot of complex code to launch a full-screen system pop-up that took the user completely out of your app. 

YouChat demonstrates the modern, inline approach. It proves how easily a beginner can use the `androidx.activity:activity-compose` library to render the secure Android system picker *directly inside* a custom layout.

### Key Takeaways for Beginners:
1.  **`minSdk 34` Requirement:** The embedded picker relies on newer Android OS capabilities. This app strictly targets API 34+ to showcase this feature natively.
2.  **State-Driven Sizing:** The picker isn't just a static box. It is wrapped in a gesture-detecting container that transitions between `COLLAPSED` (taking up the bottom third of the screen) and `EXPANDED` (taking up most of the screen).
3.  **Nested Scrolling Integration:** This is usually a scary topic for beginners, but YouChat demonstrates how to intercept swipe gestures so that swiping down on the expanded picker first scrolls the internal photo grid, and only collapses the parent UI when the grid reaches the top.

## 🛠️ Tech Stack
* **UI Toolkit:** Jetpack Compose
* **Language:** Kotlin
* **Media Picker:** Android Embedded Photo Picker (`PickVisualMedia`)
* **Video Player:** AndroidX Media3 (ExoPlayer)
* **Image Loading:** Coil (with Video Frames extension)
* **Architecture:** State Hoisting, Single Activity (`MainActivity.kt`)

## 📚 Read My Code!
If you are here to learn, start by opening `app/src/main/java/com/roxy/youchat/MainActivity.kt`. 

Because I built this while learning the concepts myself, **I have heavily documented the code specifically for other beginners.** The comments explain exactly *why* certain lifecycle and state-management decisions were made, particularly around:
* Clearing URI lists to prevent state bugs.
* Using `DisposableEffect` to prevent ExoPlayer memory leaks.
* Making the nested swipe gestures work smoothly.

## 💻 Getting Started
To run this project locally:
1. Clone this repository.
2. Open the project in Android Studio.
3. Allow Gradle to sync.
4. **Crucial:** Launch the app on an Emulator or Physical Device running **API Level 34 (Android 14)** or higher. The embedded picker will not render correctly on older Android versions!
