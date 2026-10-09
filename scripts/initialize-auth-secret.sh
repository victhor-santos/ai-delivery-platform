#!/usr/bin/env bash
# Linux/macOS equivalent of initialize-auth-secret.ps1. Usage: scripts/initialize-auth-secret.sh [env-file]
set -euo pipefail

env_file="${1:-$(dirname "$0")/../.env}"
if [[ ! -f "$env_file" ]]; then
    echo 'Create the environment file from .env.example before generating the authentication keys.' >&2
    exit 1
fi

# Writes NAME=<generated value> unless the file already has a non-empty entry; never prints the value.
set_missing_entry() {
    local name="$1" generator="$2" entries entry temporary
    entries=$(grep -c "^$name=" "$env_file" || true)
    if (( entries > 1 )); then
        echo "Duplicate $name entries. Resolve them before generating a value." >&2
        exit 1
    fi
    if (( entries == 1 )) && grep -q "^$name=[^[:space:]]" "$env_file"; then
        echo "$name is already configured; the environment file was preserved."
        return
    fi
    entry="$name=$($generator)"
    temporary=$(mktemp "$env_file.XXXXXX")
    trap 'rm -f "$temporary"' EXIT
    if (( entries == 1 )); then
        awk -v name="$name" -v entry="$entry" 'index($0, name "=") == 1 { print entry; next } { print }' \
            "$env_file" > "$temporary"
    else
        cat "$env_file" > "$temporary"
        [[ -s "$temporary" && -n "$(tail -c 1 "$temporary")" ]] && echo >> "$temporary"
        echo "$entry" >> "$temporary"
    fi
    chmod --reference="$env_file" "$temporary" 2>/dev/null || chmod 600 "$temporary"
    mv "$temporary" "$env_file"
    trap - EXIT
    echo "A random $name was saved locally. Its value is not displayed."
}

rsa_private_key() {
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
        | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n'
}

set_missing_entry USER_AUTH_PRIVATE_KEY rsa_private_key
if grep -q '^USER_AUTH_SECRET=' "$env_file"; then
    echo 'USER_AUTH_SECRET is no longer used: tokens are now signed with USER_AUTH_PRIVATE_KEY. You may remove it.'
fi
