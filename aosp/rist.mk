PRODUCT_PACKAGES += RistAssistant

PRODUCT_PACKAGES += OrganicMaps

# Required at process start by any app bundling GMS client libraries (Organic Maps).
PRODUCT_PACKAGES += GmsCompatLib

PRODUCT_PACKAGES += RistDialerStrings

# Must ship in the same image as aosp/patches/0002 and the NetworkLocationPromptActivity wording.
PRODUCT_PACKAGES += RistSettingsStrings

PRODUCT_COPY_FILES += \
    vendor/rist/pixel-phone/aosp/sysconfig/rist-privapp-permissions-watch.rist.assistant.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/rist-privapp-permissions-watch.rist.assistant.xml

PRODUCT_COPY_FILES += \
    vendor/rist/pixel-phone/aosp/init/rist-provision-do.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/rist-provision-do.rc \
    vendor/rist/pixel-phone/aosp/init/rist-provision-do.sh:$(TARGET_COPY_OUT_SYSTEM)/etc/rist/rist-provision-do.sh

# A public image must not carry an adb key; set RIST_PUBLIC_BUILD=true.
ifneq ($(RIST_PUBLIC_BUILD),true)
PRODUCT_COPY_FILES += \
    vendor/rist/pixel-phone/aosp/adb_keys:$(TARGET_COPY_OUT_PRODUCT)/etc/security/adb_keys
endif

# Both names required: with ro.boot.theme=1 bootanimation looks up only bootanimation-dark.zip.
PRODUCT_COPY_FILES += \
    vendor/rist/pixel-phone/aosp/bootanimation/bootanimation.zip:$(TARGET_COPY_OUT_PRODUCT)/media/bootanimation.zip \
    vendor/rist/pixel-phone/aosp/bootanimation/bootanimation.zip:$(TARGET_COPY_OUT_PRODUCT)/media/bootanimation-dark.zip

# Path (no .x509.pem suffix) is resolved relative to the tree top by Soong and relative to
# $KEY_DIR by sign_target_files_apks; keys/otareserve is the one spelling that satisfies both.
_rist_ota_reserve := keys/otareserve
ifneq ($(RIST_NO_OTA_RESERVE),true)
  ifeq (,$(wildcard $(_rist_ota_reserve).x509.pem))
    $(error Rist reserve OTA cert: $(_rist_ota_reserve).x509.pem is not in the tree. Copy the PUBLIC certificate -- never the .pk8 -- to BOTH $(_rist_ota_reserve).x509.pem and keys/stallion/otareserve.x509.pem before building. Building without it ships a single-certificate otacerts.zip, and that is PERMANENT for every handset flashed with the result. To do that deliberately, build with RIST_NO_OTA_RESERVE=true)
  endif
  PRODUCT_EXTRA_OTA_KEYS += $(_rist_ota_reserve)
  # Recovery-side otacerts.zip is written from extra_RECOVERY_keys, not extra_ota_keys.
  PRODUCT_EXTRA_RECOVERY_KEYS += $(_rist_ota_reserve)
endif

_rist_main_mk := $(wildcard $(BUILD_SYSTEM)/main.mk)
ifeq (,$(_rist_main_mk))
  $(error Rist debranding: cannot find $(BUILD_SYSTEM)/main.mk to verify the module-overrides mechanism)
endif
ifeq (,$(shell grep -l 'define module-overrides' $(_rist_main_mk)))
  $(error Rist debranding: build/make/core/main.mk no longer defines module-overrides. The ETC.<module>.OVERRIDES suppression in aosp/rist.mk is now a NO-OP and the GrapheneOS apps would ship)
endif

# Module names, not package names. GmsCompatLib must stay out of this list.
_rist_debrand_modules := \
    InfoApp \
    SetupWizard2 \
    etc_permissions_app.grapheneos.setupwizard \
    AppStore \
    privapp-permissions_app.grapheneos.apps.xml \
    LogViewer \
    etc_permissions_app.grapheneos.logviewer.xml \
    PdfViewerGOS \
    GmsCompat \
    GmsCompatConfig \
    etc_default-permissions_app.grapheneos.gmscompat.xml \
    etc_sysconfig_app.grapheneos.gmscompat.xml \
    LocalContactsBackup \
    Auditor

_rist_degoogle_modules := \
    PixelCameraServices \
    com.google.android.camerax.extensions

# ETC. not PACKAGES.: package_internal.mk unconditionally overwrites PACKAGES.<m>.OVERRIDES.
ETC.RistAssistant.OVERRIDES := $(_rist_debrand_modules) $(_rist_degoogle_modules)

_rist_dpoi_mk := $(wildcard $(BUILD_SYSTEM)/dex_preopt_odex_install.mk)
ifeq (,$(_rist_dpoi_mk))
  $(error Rist dexpreopt: cannot find $(BUILD_SYSTEM)/dex_preopt_odex_install.mk to verify the DEXPREOPT_DISABLED_MODULES mechanism)
endif
ifeq (,$(shell grep -l 'DEXPREOPT_DISABLED_MODULES' $(_rist_dpoi_mk)))
  $(error Rist dexpreopt: build/make/core/dex_preopt_odex_install.mk no longer reads DEXPREOPT_DISABLED_MODULES. The AOT suppression in aosp/rist.mk is now a NO-OP and the image would ship .odex compiled from Google bytecode again)
endif

# A wrong module name here fails silently; there is no existence check for this variable.
DEXPREOPT_DISABLED_MODULES += \
    EuiccGoogle \
    EuiccSupportPixel-P23 \
    PixelModemService
