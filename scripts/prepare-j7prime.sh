#!/bin/bash
set -euo pipefail

PRODUCT_MK="device/samsung/on7xelte/lineage_on7xelte.mk"

echo "Preparing lightweight Galaxy J7 Prime build..."

test -f "$PRODUCT_MK"
grep -q 'PRODUCT_DEVICE := on7xelte' "$PRODUCT_MK"
grep -q 'PRODUCT_MODEL := Galaxy J7 Prime' "$PRODUCT_MK"
grep -q 'TARGET_GAPPS_ARCH := arm64' "$PRODUCT_MK"
test -d vendor/gapps/arm64

if ! grep -q 'vendor/gapps/arm64/arm64-vendor.mk' "$PRODUCT_MK"; then
cat >> "$PRODUCT_MK" <<'EOF'

# Integrated MindTheGapps for Android 12L / ARM64.
$(call inherit-product, vendor/gapps/arm64/arm64-vendor.mk)
EOF
fi

cat >> "$PRODUCT_MK" <<'EOF'

# J7 Prime Slim Profile
PRODUCT_PACKAGES -= FamilyLinkParentalControls
PRODUCT_PACKAGES -= MarkupGoogle_v2
PRODUCT_PACKAGES -= SpeechServicesByGoogle
PRODUCT_PACKAGES -= Velvet
PRODUCT_PACKAGES -= talkback
PRODUCT_PACKAGES -= GoogleFeedback
PRODUCT_PACKAGES -= Wellbeing
PRODUCT_PACKAGES -= AndroidAutoStub

# Optional Lineage applications.
PRODUCT_PACKAGES -= Eleven
PRODUCT_PACKAGES -= Jelly
PRODUCT_PACKAGES -= Recorder
PRODUCT_PACKAGES -= AudioFX
PRODUCT_PACKAGES -= Aperture
PRODUCT_PACKAGES -= Glimpse
PRODUCT_PACKAGES -= MusicFX
EOF

OVERLAY="device/samsung/on7xelte/overlay/frameworks/base/core/res/res/values"
mkdir -p "$OVERLAY"
cat > "$OVERLAY/config.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 2 = UiModeManager.MODE_NIGHT_YES -->
    <integer name="config_defaultNightMode">2</integer>
</resources>
EOF

echo "Configured:"
echo "  Device: on7xelte"
echo "  Model: Galaxy J7 Prime"
echo "  ABI: arm64-v8a"
echo "  Android: 12L / LineageOS 19.1"
echo "  GApps: integrated MindTheGapps sigma"
echo "  Magisk: excluded from ROM"
