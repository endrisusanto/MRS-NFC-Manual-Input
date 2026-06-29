param(
    [string]$BaseUrl = "http://107.102.8.148/MERS",
    [string]$GenId = "16756586",
    [string]$Password = "27051994",
    [string]$FromDate = "2026-06-29",
    [string]$ToDate = "2026-07-02"
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "   TESTING MERS FINAL-ORDER SYNC         " -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Target URL : $BaseUrl"
Write-Host "User ID    : $GenId"
Write-Host "From Date  : $FromDate"
Write-Host "To Date    : $ToDate"
Write-Host "-----------------------------------------"

$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()

Write-Host "1. Melakukan login ke MERS dengan user $GenId..." -ForegroundColor Yellow
$loginUrl = "$BaseUrl/auth/login"

try {
    $loginRes = Invoke-WebRequest -Uri $loginUrl -Method Post -Body @{ identity = $GenId; password = $Password } -WebSession $session -TimeoutSec 12 -UseBasicParsing
    Write-Host "   Login sukses! Cookie diperoleh." -ForegroundColor Green
    
    Write-Host "   Mengekstrak Internal User ID dari order/pilihmenu..." -ForegroundColor Yellow
    $pilihMenuRes = Invoke-WebRequest -Uri "$BaseUrl/order/pilihmenu" -WebSession $session -TimeoutSec 12 -UseBasicParsing
    $InternalId = $GenId
    if ($pilihMenuRes.Content -match "reports/generate/[^/]+/[^/]+/(\d+)") {
        $InternalId = $matches[1]
        Write-Host "   Internal User ID ditemukan: $InternalId" -ForegroundColor Green
    } else {
        Write-Warning "   Internal User ID tidak ditemukan, menggunakan GenId: $GenId"
    }
} catch {
    Write-Warning "   Gagal login dengan user ${GenId}: $($_)"
}

# Try fetching reports with the user session first
$reportUrl = "$BaseUrl/reports/generate/$FromDate/$ToDate/$InternalId/final-order"
Write-Host "2. Mengunduh data report final-order dari URL:" -ForegroundColor Yellow
Write-Host "   $reportUrl" -ForegroundColor Gray

$htmlBody = ""
$success = $false

try {
    $reportRes = Invoke-WebRequest -Uri $reportUrl -WebSession $session -TimeoutSec 12 -UseBasicParsing
    $htmlBody = $reportRes.Content
    if ($htmlBody -match "<table") {
        Write-Host "   Report berhasil diunduh menggunakan session user!" -ForegroundColor Green
        $success = $true
    }
} catch {
    Write-Host "   Gagal mengunduh report dengan user session (akses ditolak/restricted)." -ForegroundColor Red
}

# If user session fails, fallback to master account authentication (same pattern as Kotlin & Rust backends)
if (-not $success) {
    Write-Host "3. Fallback: Login menggunakan master account '14829575'..." -ForegroundColor Yellow
    $masterSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    try {
        $masterLogin = Invoke-WebRequest -Uri $loginUrl -Method Post -Body @{ identity = "14829575"; password = "23051995" } -WebSession $masterSession -TimeoutSec 12 -UseBasicParsing
        Write-Host "   Master login sukses!" -ForegroundColor Green
        
        Write-Host "4. Mengunduh report user $GenId via master session..." -ForegroundColor Yellow
        $masterReportRes = Invoke-WebRequest -Uri $reportUrl -WebSession $masterSession -TimeoutSec 12 -UseBasicParsing
        $htmlBody = $masterReportRes.Content
        if ($htmlBody -match "<table") {
            Write-Host "   Report berhasil diunduh via master session!" -ForegroundColor Green
            $success = $true
        }
    } catch {
        Write-Error "   Gagal melakukan login master atau mengunduh report: $($_)"
    }
}

if (-not $success -or [string]::IsNullOrEmpty($htmlBody)) {
    Write-Error "Gagal mendapatkan data report HTML dari MERS server."
    exit 1
}

Write-Host "5. Melakukan parsing tabel HTML..." -ForegroundColor Yellow

$orders = @()
$rowMatches = [regex]::Matches($htmlBody, '(?is)<tr[^>]*>(.*?)</tr>')

Write-Host "   Ditemukan $($rowMatches.Count) baris tabel."

foreach ($row in $rowMatches) {
    $cells = [regex]::Matches($row.Groups[1].Value, '(?is)<td[^>]*>(.*?)</td>')
    if ($cells.Count -ge 7) {
        $tanggal = [regex]::Replace($cells[0].Groups[1].Value, '<[^>]+>', ' ').Trim() -replace '\s+', ' '
        $jadwal = [regex]::Replace($cells[1].Groups[1].Value, '<[^>]+>', ' ').Trim() -replace '\s+', ' '
        $line = [regex]::Replace($cells[2].Groups[1].Value, '<[^>]+>', ' ').Trim() -replace '\s+', ' '
        $nama = [regex]::Replace($cells[3].Groups[1].Value, '<[^>]+>', ' ').Trim() -replace '\s+', ' '
        $gen = [regex]::Replace($cells[4].Groups[1].Value, '<[^>]+>', ' ').Trim() -replace '\s+', ' '
        $part = [regex]::Replace($cells[5].Groups[1].Value, '<[^>]+>', ' ').Trim() -replace '\s+', ' '
        $menu = [regex]::Replace($cells[6].Groups[1].Value, '<[^>]+>', ' ').Trim() -replace '\s+', ' '
        
        # Check Status Ambil
        $statusCell = $cells[7].Groups[1].Value
        $status = "Belum Diambil"
        if ($statusCell -match "Sudah\s+Diambil") {
            $status = "Sudah Diambil"
        }
        
        $orders += [PSCustomObject]@{
            Tanggal = $tanggal
            Jadwal = $jadwal
            Line = $line
            Nama = $nama
            GEN = $gen
            Part = $part
            Menu = $menu
            Status = $status
        }
    }
}

Write-Host "--------------------------------------------------------------------------------" -ForegroundColor Cyan
Write-Host "                                 HASIL SYNC DATA                                " -ForegroundColor Cyan
Write-Host "--------------------------------------------------------------------------------" -ForegroundColor Cyan
$orders | Format-Table -AutoSize
Write-Host "--------------------------------------------------------------------------------" -ForegroundColor Cyan
Write-Host "Sync test selesai!" -ForegroundColor Green
