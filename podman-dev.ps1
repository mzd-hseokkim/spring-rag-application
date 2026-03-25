# Podman 개발 환경 실행 스크립트 (Windows PowerShell)
#
# Podman은 Docker Desktop의 무료 대안입니다.
# - 기업 환경에서도 무료 (Docker Desktop은 대기업 유료)
# - docker-compose 파일을 그대로 사용 가능
# - rootless 컨테이너 지원
#
# === 설치 방법 ===
# 1. winget install RedHat.Podman
# 2. winget install RedHat.Podman-Desktop   (선택: GUI 관리 도구)
# 3. podman machine init
# 4. podman machine start
#
# === 사용법 ===
# .\podman-dev.ps1 up      # 전체 서비스 시작
# .\podman-dev.ps1 down    # 전체 서비스 중지
# .\podman-dev.ps1 logs    # 로그 보기
# .\podman-dev.ps1 build   # 이미지 재빌드 후 시작

param(
    [Parameter(Position=0)]
    [ValidateSet("up", "down", "logs", "build", "ps")]
    [string]$Action = "up"
)

$ComposeFile = "docker-compose-dev.yml"

# podman-compose가 없으면 pip로 설치 안내
$podmanCompose = Get-Command podman-compose -ErrorAction SilentlyContinue
if (-not $podmanCompose) {
    $podmanCompose = Get-Command podman -ErrorAction SilentlyContinue
    if (-not $podmanCompose) {
        Write-Host "Podman이 설치되어 있지 않습니다." -ForegroundColor Red
        Write-Host "설치: winget install RedHat.Podman" -ForegroundColor Yellow
        exit 1
    }
    # podman compose (built-in subcommand) 사용
    $cmd = "podman"
    $composeCmd = "compose"
} else {
    $cmd = "podman-compose"
    $composeCmd = ""
}

switch ($Action) {
    "up" {
        Write-Host "Starting all services..." -ForegroundColor Green
        if ($composeCmd) {
            & $cmd $composeCmd -f $ComposeFile up -d
        } else {
            & $cmd -f $ComposeFile up -d
        }
    }
    "down" {
        Write-Host "Stopping all services..." -ForegroundColor Yellow
        if ($composeCmd) {
            & $cmd $composeCmd -f $ComposeFile down
        } else {
            & $cmd -f $ComposeFile down
        }
    }
    "logs" {
        if ($composeCmd) {
            & $cmd $composeCmd -f $ComposeFile logs -f
        } else {
            & $cmd -f $ComposeFile logs -f
        }
    }
    "build" {
        Write-Host "Rebuilding and starting..." -ForegroundColor Green
        if ($composeCmd) {
            & $cmd $composeCmd -f $ComposeFile up -d --build
        } else {
            & $cmd -f $ComposeFile up -d --build
        }
    }
    "ps" {
        if ($composeCmd) {
            & $cmd $composeCmd -f $ComposeFile ps
        } else {
            & $cmd -f $ComposeFile ps
        }
    }
}
