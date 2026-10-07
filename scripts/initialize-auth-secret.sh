#!/usr/bin/env bash
# Linux/macOS equivalent of initialize-auth-secret.ps1. Usage: scripts/initialize-auth-secret.sh [env-file]
set -euo pipefail

env_file="${1:-$(dirname "$0")/../.env}"
if [[ ! -f "$env_file" ]]; then
    echo 'Create the environment file from .env.example before generating the authentication secret.' >&2
    exit 1
fi

entries=$(grep -c '^USER_AUTH_SECRET=' "$env_file" || true)
if (( entries > 1 )); then
    echo 'Duplicate USER_AUTH_SECRET entries. Resolve them before generating a key.' >&2
    exit 1
fi
if (( entries == 1 )) && grep -q '^USER_AUTH_SECRET=[^[:space:]]' "$env_file"; then
    echo 'USER_AUTH_SECRET is already configured; the environment file was preserved.'
    exit 0
fi

entry="USER_AUTH_SECRET=$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
temporary=$(mktemp "$env_file.XXXXXX")
trap 'rm -f "$temporary"' EXIT
if (( entries == 1 )); then
    awk -v entry="$entry" '/^USER_AUTH_SECRET=/ { print entry; next } { print }' "$env_file" > "$temporary"
else
    cat "$env_file" > "$temporary"
    [[ -s "$temporary" && -n "$(tail -c 1 "$temporary")" ]] && echo >> "$temporary"
    echo "$entry" >> "$temporary"
fi
chmod --reference="$env_file" "$temporary" 2>/dev/null || chmod 600 "$temporary"
mv "$temporary" "$env_file"
trap - EXIT
echo 'A random authentication secret was saved locally. Its value is not displayed.'
