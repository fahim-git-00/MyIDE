#!/data/data/com.termux/files/usr/bin/bash
set -e
DST="app/src/main/jniLibs/arm64-v8a"

# Map old SONAME → new file name
declare -A MAP
MAP["libjli.so"]="libjdk_libjli.so"
MAP["libjava.so"]="libjdk_libjava.so"
MAP["libjvm.so"]="libjdk_libjvm.so"
MAP["libjsig.so"]="libjdk_libjsig.so"
MAP["libverify.so"]="libjdk_libverify.so"
MAP["libzip.so"]="libjdk_libzip.so"
MAP["libnet.so"]="libjdk_libnet.so"
MAP["libnio.so"]="libjdk_libnio.so"
MAP["libextnet.so"]="libjdk_libextnet.so"
MAP["librmi.so"]="libjdk_librmi.so"
MAP["libjaas.so"]="libjdk_libjaas.so"
MAP["libmanagement.so"]="libjdk_libmanagement.so"
MAP["libmanagement_ext.so"]="libjdk_libmanagement_ext.so"
MAP["libmanagement_agent.so"]="libjdk_libmanagement_agent.so"
MAP["libinstrument.so"]="libjdk_libinstrument.so"
MAP["libjimage.so"]="libjdk_libjimage.so"
MAP["libattach.so"]="libjdk_libattach.so"
MAP["libjdwp.so"]="libjdk_libjdwp.so"
MAP["libdt_socket.so"]="libjdk_libdt_socket.so"
MAP["libsyslookup.so"]="libjdk_libsyslookup.so"
MAP["libprefs.so"]="libjdk_libprefs.so"
MAP["libj2gss.so"]="libjdk_libj2gss.so"
MAP["libj2pcsc.so"]="libjdk_libj2pcsc.so"
MAP["libj2pkcs11.so"]="libjdk_libj2pkcs11.so"
MAP["libsctp.so"]="libjdk_libsctp.so"
MAP["libjavajpeg.so"]="libjdk_libjavajpeg.so"
MAP["liblcms.so"]="libjdk_liblcms.so"
MAP["lible.so"]="libjdk_lible.so"
MAP["libfontmanager.so"]="libjdk_libfontmanager.so"
MAP["libmlib_image.so"]="libjdk_libmlib_image.so"
MAP["libawt.so"]="libjdk_libawt.so"
MAP["libawt_headless.so"]="libjdk_libawt_headless.so"
MAP["libawt_xawt.so"]="libjdk_libawt_xawt.so"
MAP["libjawt.so"]="libjdk_libjawt.so"
MAP["libjsound.so"]="libjdk_libjsound.so"
MAP["libsplashscreen.so"]="libjdk_libsplashscreen.so"
MAP["libandroid-shmem.so"]="libtermux_android-shmem.so"
MAP["libz.so.1"]="libtermux_z.so"
MAP["libcrypto.so.3"]="libtermux_crypto.so"
MAP["libssl.so.3"]="libtermux_ssl.so"

rewrite() {
  local f="$1"
  local base="$(basename "$f")"
  echo "  SONAME  $base"
  patchelf --set-soname "$base" "$f"
  for old in "${!MAP[@]}"; do
    local new="${MAP[$old]}"
    if patchelf --print-needed "$f" 2>/dev/null | grep -qx "$old"; then
      echo "    NEEDED $old → $new"
      patchelf --replace-needed "$old" "$new" "$f"
    fi
  done
}

for f in "$DST"/*.so; do
  rewrite "$f"
done

echo "done. verifying libjvm needed:"
patchelf --print-needed "$DST/libjdk_libjvm.so"
