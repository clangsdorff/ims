#!/system/bin/sh
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
# com.android.phone reads config_ims_mmtel_package only at start and GSI apps flip it with their own overlays
for slot in 0 1; do
    cmd phone ims set-ims-service -s $slot -d -f 0,1 com.langsdorff.flossims
done
