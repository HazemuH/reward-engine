#!/bin/bash
# publish-to-origin.sh — push local `main` to the GitHub remote's
# `feat/master` branch, with .claude/ and CLAUDE.md stripped out of every
# commit.
#
# Why feat/master and not main: origin's `main` is a static landing README
# describing the project — the actual code lives on `feat/master`. Local
# `main` is the full repo (code + AI-workflow files); this script is what
# turns it into the filtered branch that gets published.
#
# Why filtered at all: this repo's AI-workflow files (.claude/skills,
# CLAUDE.md) are meant to stay local/internal, not published — only the
# actual project files go to GitHub.
#
# Usage: ./scripts/publish-to-origin.sh
#
# This force-pushes the regenerated history to origin's feat/master. Safe as
# long as that branch is treated as a mirror (nobody commits to it directly
# on GitHub) — if that ever changes, this script needs to change too.

set -euo pipefail

TARGET_BRANCH="feat/master"

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ -n "$(git status --porcelain)" ]; then
	echo "Working tree not clean — commit or stash first." >&2
	exit 1
fi

CURRENT_BRANCH="$(git branch --show-current)"
if [ "$CURRENT_BRANCH" != "main" ]; then
	echo "Run this from main (currently on $CURRENT_BRANCH)." >&2
	exit 1
fi

git branch -D origin-publish 2>/dev/null || true
git checkout -b origin-publish main

git filter-branch --force --prune-empty \
	--index-filter 'git rm -r --cached --ignore-unmatch .claude CLAUDE.md' \
	-- origin-publish

echo "Pushing filtered history to origin/$TARGET_BRANCH (force) ..."
git push --force origin "origin-publish:$TARGET_BRANCH"

git checkout main
git branch -D origin-publish
git for-each-ref --format="%(refname)" refs/original/ | xargs -r -n1 git update-ref -d

echo "Done. origin/$TARGET_BRANCH now mirrors main, minus .claude/ and CLAUDE.md."
