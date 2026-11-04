#!/usr/bin/env bash
# Manual release fallback (GitHub Actions): downloads the build of this commit that passed every
# check on master into dist/ and writes its version to $GITHUB_OUTPUT. Nothing is rebuilt.
set -euo pipefail
repo="$GITHUB_REPOSITORY"

if [[ "$GITHUB_REF" != refs/heads/master ]]; then
  echo "::error::Run the release fallback on master."
  exit 1
fi
run=$(gh api "repos/$repo/actions/workflows/build.yml/runs?head_sha=$GITHUB_SHA&event=push&status=success" \
  --jq '.workflow_runs[0].id // empty')
if [[ -z "$run" ]]; then
  echo "::error::No passed build of master's latest commit ($GITHUB_SHA) yet. Wait for it to finish, or fix it if it failed."
  exit 1
fi
name=$(gh api "repos/$repo/actions/runs/$run/artifacts" \
  --jq '[.artifacts[] | select(.name | startswith("metro-launcher-"))][0].name // empty')
if [[ -z "$name" ]]; then
  echo "::error::Build $run has no metro-launcher artifact (it may have expired)."
  exit 1
fi
version="${name#metro-launcher-}"
if gh api "repos/$repo/git/ref/tags/v$version" --silent 2>/dev/null ||
   gh api "repos/$repo/releases/tags/v$version" --silent 2>/dev/null; then
  echo "::error::v$version is already released."
  exit 1
fi
gh run download "$run" -R "$repo" -n "$name" -D dist
echo "Publishing build $run ($name) as v$version"
ls -l dist
echo "version=$version" >> "$GITHUB_OUTPUT"
