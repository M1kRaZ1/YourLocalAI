# Your Local AI app

**Your Local AI** is an Android 6.0+ (API 23+) application engineered to do the impossible (or is it ?): execute compact **270M parameter LLMs** locally on **legacy 32-bit ARM architectures**. 

The goal? Push hardware and software optimization to its absolute limits to bring on-device AI to extreme, low-spec configurations. theoretically running on hardware as old as the legendary **Samsung Galaxy S2**. 

No cloud, no API keys. Just raw, close-to-the-metal optimization.

---

## The Breakthrough: Gemma 3 270M @ 8-bit
While running a language model on a 32-bit legacy device sounds like a pipe dream due to severe RAM bottlenecks (Galaxy S2 maxes out at 1GB RAM total), this project successfully loads and runs **Gemma 3 270M quantized in 8-bit (INT8)**. 

* **ARM NEON Acceleration:** Uses hand-optimized ARMv7 NEON assembly instructions to accelerate 8-bit matrix multiplication (GEMM) directly on the CPU.
* **Aggressive Memory Mapping:** Uses `mmap` to stream the model weight allocations straight from the storage without blowing up the physical RAM.
* **Low Context Footprint:** Heavily optimized KV cache to prevent the Android Low Memory Killer (LMK) from wiping the app process.

---

## Tech Stack & Requirements

* **Minimum OS:** Android 6.0 Marshmallow (API 23)
* **Architecture:** `armeabi-v7a` (32-bit ARM v7 with NEON support). **x86 CPUs are also supported** for compatibility with older Intel Atom devices and emulators.
* **Target Model:** Gemma 3 270M (INT8 / Q8_0)
* **Core Engine:** Custom C++ inference engine compiled via Android NDK

---

## 🚀 Getting Started

If you wish to fork my base project and improve it, feel free so! Also, you will require updating the MainActivity.kt in initLLMAsync section in order to use a custom AI model of ur choice
