# 🧩 Xperia 1 V camera patches

Morphe patches that run the **Sony Xperia 1 VI camera app** (`jp.co.sony.mc.cameraapp`) on the **Xperia 1 V**
(LineageOS), plus a re-sign patch for Photo Pro so both can be installed together.

## ❓ About

The Xperia 1 VI app talks to the same Sony camera HAL family as the 1 V but trips two request-validation
gates on the 1 V HAL (`objectSelectTriggerArea` must be 4 ints; `sceneDetectMode` must be accompanied by
`conditionDetectMode`). These patches inject the two shims, and make the sideloaded app see
`com.sonymobile.cameracommon`. Photo Pro needs no code changes, only the same signer (both apps declare
`SOMC_CAMERA`).

The "Memory unavailable" storage problem (LineageOS pdx234 cannot create `Android/data/<pkg>/files`) is fixed
in-app by the `Storage fallback` patch, which applies to both the new camera app and Photo Pro.

### How to use these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=alalloush/xperia-1v-camera-patches

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0-dev.1](https://github.com/alalloush/xperia-1v-camera-patches/releases/tag/v1.0.0-dev.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`dev`&nbsp;&nbsp;•&nbsp;&nbsp;3 patches total
<details open>
<summary>📦 jp.co.sony.mc.cameraapp&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Camera common visibility](#camera-common-visibility) | Lets the camera app see and use com.sonymobile.cameracommon (camera status provider, gyro calibration) when both are sideloaded: adds the <queries> entry and declares the CAMERA_STATUS_PROVIDER and CAMERA_ADDON permissions. |  |
| [Xperia 1 V camera HAL compatibility](#xperia-1-v-camera-hal-compatibility) | Makes the Xperia 1 VI camera app work on the Xperia 1 V camera HAL: truncates objectSelectTriggerArea to the 4 ints the 1 V HAL defines and sends conditionDetectMode alongside sceneDetectMode, which the 1 V HAL validates together. |  |

</details>

<details open>
<summary>📦 com.sonymobile.photopro&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Photo Pro signer alignment](#photo-pro-signer-alignment) | No code changes. Re-signs Photo Pro with the Morphe key so it can be installed next to the patched Xperia 1 VI camera app (both declare SOMC_CAMERA and must share a signer). The Android/data/com.sonymobile.photopro/files/DCIM dir must still be provisioned by root. |  |

</details>

<!-- PATCHES_END -->

### 🛠️ Building locally

- Run `./gradlew buildAndroid`
- The built patches .mpp file is found in `patches/build/libs/patches-*.mpp`
- Patch the mpp file using [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)
  like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## 📜 License

These patches are licensed under the [GNU General Public License v3.0](LICENSE)
