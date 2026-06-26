# Connection Debugging Script for MeRS Agent (Windows PowerShell)
# Output format: JSON
$ErrorActionPreference = "Stop"

function Test-TcpPort {
    param (
        [string]$ComputerName,
        [int]$Port,
        [int]$TimeoutMs = 2000
    )
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $ar = $client.BeginConnect($ComputerName, $Port, $null, $null)
        $wait = $ar.AsyncWaitHandle.WaitOne($TimeoutMs, $true)
        if ($wait -and $client.Connected) {
            $client.EndConnect($ar)
            $client.Close()
            return $true
        } else {
            $client.Close()
            return $false
        }
    } catch {
        return $false
    }
}

function Get-HttpResponse {
    param (
        [string]$Url,
        [int]$TimeoutSec = 3
    )
    try {
        $response = Invoke-WebRequest -Uri $Url -TimeoutSec $TimeoutSec -UseBasicParsing -ErrorAction Ignore
        return [PSCustomObject]@{
            Success     = $true
            StatusCode  = $response.StatusCode
            StatusDescription = $response.StatusDescription
        }
    } catch {
        return [PSCustomObject]@{
            Success = $false
            Error   = $_.Exception.Message
        }
    }
}

# 1. Test DNS Resolve
$cloudDns = try { [System.Net.Dns]::GetHostAddresses("makan.endrisusanto.my.id")[0].IPAddressToString } catch { $null }

# 2. Test TCP Ports
$mersTcp = Test-TcpPort -ComputerName "107.102.8.148" -Port 80
$gatewayTcp = Test-TcpPort -ComputerName "makan.endrisusanto.my.id" -Port 443

# 3. Test HTTP endpoints
$mersHttp = Get-HttpResponse -Url "http://107.102.8.148/MERS/cekorder.php?ping=1"
$gatewayHttp = Get-HttpResponse -Url "https://makan.endrisusanto.my.id/mers-ping"

# 4. Overall diagnosis
$status = "OK"
$recommendation = "Semua koneksi berjalan dengan baik."

if (-not $mersTcp) {
    $status = "ERROR"
    $recommendation = "Koneksi ke Intranet MeRS (107.102.8.148:80) gagal. Pastikan Anda sudah terhubung ke Wi-Fi kantor atau VPN Samsung aktif."
} elseif (-not $gatewayTcp) {
    $status = "ERROR"
    $recommendation = "Koneksi ke WebSocket Gateway (makan.endrisusanto.my.id:443) gagal. Firewall kantor mungkin memblokir port 443 ke domain ini."
} elseif (-not $gatewayHttp.Success) {
    $status = "ERROR"
    $recommendation = "WebSocket Gateway terhubung secara network, namun HTTP handshake gagal. Detail error: $($gatewayHttp.Error)"
}

$report = [PSCustomObject]@{
    Timestamp      = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
    Status         = $status
    Recommendation = $recommendation
    IntranetMers   = [PSCustomObject]@{
        IpAddress  = "107.102.8.148"
        Port80Open = $mersTcp
        HttpResponse = $mersHttp
    }
    CloudGateway   = [PSCustomObject]@{
        DomainName  = "makan.endrisusanto.my.id"
        ResolvedIp  = $cloudDns
        Port443Open = $gatewayTcp
        HttpResponse = $gatewayHttp
    }
}

$report | ConvertTo-Json -Depth 5
