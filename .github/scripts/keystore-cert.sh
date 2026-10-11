#!/usr/bin/env bash
# Prints the SHA-256 (lower-case hex) of the release key's certificate in a
# keystore. Usage: keystore-cert.sh KEYSTORE
# The store and key password is the public value "android" and the alias is
# "release": the key is protected by being only in a repository secret.
set -euo pipefail
keytool -list -v -keystore "$1" -storepass android -alias release 2>/dev/null \
  | sed -n 's/^.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f'
