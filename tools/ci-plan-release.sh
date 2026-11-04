#!/usr/bin/env bash
# Decides this build's version and whether to publish it (GitHub Actions, writes $GITHUB_OUTPUT).
#   v* tag push               -> that version, released
#   push to master            -> next version after the latest release; released when the merged
#                                PR has the label `release` (next patch) or `release:minor`
#   anything else (PRs)       -> a dev version, not released
set -euo pipefail
repo="$GITHUB_REPOSITORY"
release=false

if [[ "$GITHUB_REF" == refs/tags/v* ]]; then
  version="${GITHUB_REF_NAME#v}"
  release=true
elif [[ "$GITHUB_EVENT_NAME" == push && "$GITHUB_REF" == refs/heads/master ]]; then
  latest=$(gh api "repos/$repo/releases/latest" --jq .tag_name 2>/dev/null || echo v0.0.0)
  IFS=. read -r major minor patch <<< "${latest#v}"
  patch="${patch%%[!0-9]*}"
  pr=$(gh api "repos/$repo/commits/$GITHUB_SHA/pulls" --jq '.[0].number // empty' || true)
  labels=""
  if [[ -n "$pr" ]]; then
    labels=$(gh api "repos/$repo/issues/$pr/labels" --jq '.[].name')
  fi
  if grep -qx 'release:minor' <<< "$labels"; then
    version="$major.$((minor + 1)).0"
    release=true
  else
    version="$major.$minor.$((patch + 1))"
    grep -qx 'release' <<< "$labels" && release=true
  fi
  echo "Latest release $latest; merged PR: ${pr:-none}; labels: ${labels//$'\n'/, }"
else
  version="0.0.0-dev.$GITHUB_RUN_NUMBER"
fi

if [[ "$release" == true ]] &&
   { gh api "repos/$repo/git/ref/tags/v$version" --silent 2>/dev/null ||
     gh api "repos/$repo/releases/tags/v$version" --silent 2>/dev/null; }; then
  echo "::error::v$version already exists, so this build can't be released under it."
  exit 1
fi

echo "Version $version, release: $release"
echo "version=$version" >> "$GITHUB_OUTPUT"
echo "release=$release" >> "$GITHUB_OUTPUT"
