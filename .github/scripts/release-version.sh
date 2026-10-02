#!/usr/bin/env bash
# Verifies a release tag and prints the Maven version of the release.
#
# Usage: release-version.sh <tag> <fess-version>
#
# The tag must be an annotated tag of the form vMAJOR.MINOR.PATCH on a commit
# of origin/main. The Maven version is <fess-version>-MAJOR.MINOR.PATCH; Fess
# lists a plugin version in its admin UI only when it starts with the Fess
# version.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <tag> <fess-version>" >&2
  exit 2
fi
tag="$1"
fess_version="$2"

number='(0|[1-9][0-9]*)'
if ! [[ "$tag" =~ ^v$number\.$number\.$number$ ]]; then
  echo "error: tag '$tag' has not the form vMAJOR.MINOR.PATCH" >&2
  exit 1
fi
if ! [[ "$fess_version" =~ ^$number\.$number\.$number$ ]]; then
  echo "error: Fess version '$fess_version' has not the form MAJOR.MINOR.PATCH" >&2
  exit 1
fi
if [ "$(git cat-file -t "refs/tags/$tag" 2>/dev/null)" != "tag" ]; then
  echo "error: tag '$tag' is missing or is a lightweight tag" >&2
  exit 1
fi
if ! git merge-base --is-ancestor "refs/tags/$tag^{commit}" refs/remotes/origin/main; then
  echo "error: tag '$tag' is outside origin/main" >&2
  exit 1
fi
echo "${fess_version}-${tag#v}"
