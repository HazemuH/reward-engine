#!/bin/bash
# publish-to-origin.sh — push `main` to the GitHub remote with .claude/ and
# CLAUDE.md stripped out of every commit.
#
# Why: this repo's AI-workflow files (.claude/skills, CLAUDE.md) are meant to
# stay local/internal, not published to the shared GitHub repo — only the
# actual project files go there. Local `main` keeps everything; the remote's
# `main` is a filtered view regenerated from it each time this runs.
#
# Usage: ./scripts/publish-to-origin.sh
#
# This force-pushes the regenerated history to origin's main. Safe as long as
# origin's main is treated as a mirror (nobody commits to it directly) — if
# that ever changes, this script needs to change too.

set -euo pipefail

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

echo "Pushing filtered history to origin/main (force) ..."
git push --force origin origin-publish:main

git checkout main
git branch -D origin-publish
git for-each-ref --format="%(refname)" refs/original/ | xargs -r -n1 git update-ref -d

echo "Done. origin/main now mirrors main, minus .claude/ and CLAUDE.md."
