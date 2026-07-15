# librime release source requirement

The current local development build still links the historical prebuilt
`librime_jni.so`. It is not sufficient for a public GPLv3 release by itself.

Before building a public release, vendor the exact librime source revision here,
including all Android build dependencies and any local patches. Add a
`SOURCE_LOCK.md` containing the upstream commit, patch hashes, NDK version, and
reproducible arm64-v8a build command. The `verifyOpenSourceRelease` Gradle task
enforces this requirement.
