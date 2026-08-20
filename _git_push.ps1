$ErrorActionPreference = 'Stop'
$repo = 'C:\Users\Asus\Desktop\uniselect-agent-backend'
Set-Location $repo

# 1) init (not yet a git repo)
git init -b main

# 2) remote
$existing = git remote get-url origin 2>$null
if (-not $existing) {
    git remote add origin 'https://github.com/CHANSingYeungSunny/uniselect-agent-backend.git'
    Write-Output 'remote added'
} else {
    Write-Output "remote exists: $existing"
}

# 3) ignore build/output artifacts
$ignore = @'
# Build output
target/
bin/
build/
out/

# IDE
.idea/
*.iml
*.iml
.vscode/

# OS
Thumbs.db
Desktop.ini

# Local sensitive
.env
.env.*
!.env.example

# JDK distribution accidentally copied into repo
oracleJdk-26/

# Temp helper scripts
_git_push.ps1
'@
Set-Content -Path '.gitignore' -Value $ignore -Encoding utf8

# 4) stage everything
git add -A

# 5) commit
$msg = "feat: UniSelect CS Agent - mock pipeline + prompt-injection defense + demo"
git commit -m $msg

# 6) push (set upstream, allow first push to non-empty remote)
git push -u origin main --force-with-lease
