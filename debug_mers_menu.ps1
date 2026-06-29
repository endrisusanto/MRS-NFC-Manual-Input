param(
    [string]$BaseUrl = "http://107.102.8.148/MERS",
    [string]$GenId = "",
    [string]$Password = "",
    [string]$Date = (Get-Date -Format "yyyy-MM-dd"),
    [int]$Days = 2,
    [string[]]$MealIds = @("2", "3"),
    [string[]]$Lokets = @("1", "2", "3", "4", "5", "6"),
    [string]$OutFile = "mers-menu-debug.json"
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
if ($Days -lt 1) { $Days = 1 }
$Dates = 0..($Days - 1) | ForEach-Object { (Get-Date $Date).AddDays($_).ToString("yyyy-MM-dd") }

function Test-TcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 3000)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        if (-not $task.Wait($TimeoutMs)) { return $false }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Invoke-Check {
    param([string]$Url, [Microsoft.PowerShell.Commands.WebRequestSession]$Session = $null)
    try {
        $args = @{ Uri = $Url; TimeoutSec = 12; UseBasicParsing = $true }
        if ($Session) { $args.WebSession = $Session }
        $res = Invoke-WebRequest @args
        [PSCustomObject]@{
            ok = $true
            status = [int]$res.StatusCode
            contentType = $res.Headers["Content-Type"]
            length = $res.Content.Length
            body = $res.Content
        }
    } catch {
        [PSCustomObject]@{
            ok = $false
            error = $_.Exception.Message
            body = ""
        }
    }
}

function Parse-Json($Text) {
    try { $Text | ConvertFrom-Json } catch { $null }
}

function Short-Body($Text, [int]$Max = 1200) {
    if (-not $Text) { return "" }
    $clean = ($Text -replace "\s+", " ").Trim()
    if ($clean.Length -le $Max) { return $clean }
    $clean.Substring(0, $Max)
}

function Menu-Names-From-Html($Html) {
    $out = @()
    $matches = [regex]::Matches($Html, '(?is)(?:value|data-id|data-menu-id|data-schedule-menu-id)\s*=\s*["'']?(\d+)["'']?')
    foreach ($m in $matches) {
        $id = $m.Groups[1].Value
        $matchIdx = $m.Index
        
        $bodyPre = $Html.Substring(0, $matchIdx)
        $startIdx = $matchIdx
        $tags = @("<label", "<option", "<div", "<tr")
        foreach ($tag in $tags) {
            $lastTagIdx = $bodyPre.LastIndexOf($tag)
            if ($lastTagIdx -ge 0 -and $lastTagIdx -lt $startIdx) {
                $startIdx = $lastTagIdx
            }
        }
        
        $afterMatch = $Html.Substring($matchIdx + $m.Length)
        $nextIdx = -1
        $nextRe = [regex]::Match($afterMatch, '(?i)name=["'']?menusaya["'']?|type=["'']?radio["'']?|<option')
        if ($nextRe.Success) {
            $nextIdx = $matchIdx + $m.Length + $nextRe.Index
        } else {
            $nextIdx = $Html.Length
        }
        
        $chunk = $Html.Substring($startIdx, $nextIdx - $startIdx)
        
        $cleanChunk = [regex]::Replace($chunk, '(?is)<input[^>]*>', '')
        $cleanChunk = [regex]::Replace($cleanChunk, '(?is)<[^>]+(?:class|id)\s*=\s*["'']?[^"'']*(?:menu-item-name|menu-info|detail|qty|stock|balance)[^"'']*["'']?[^>]*>.*?<\/[^>]+>', '')
        
        $name = ""
        $titleRe = [regex]::Match($chunk, '(?is)<[^>]+(?:class|id)\s*=\s*["'']?[^"'']*(?:menu-title|menu-name|item-title)[^"'']*["'']?[^>]*>(.*?)</[^>]+>')
        if (-not $titleRe.Success) {
            $titleRe = [regex]::Match($chunk, '(?is)<h[2-5][^>]*>(.*?)</h[2-5]>')
        }
        if (-not $titleRe.Success) {
            $titleRe = [regex]::Match($chunk, '(?is)<strong[^>]*>(.*?)</strong>')
        }
        if (-not $titleRe.Success) {
            $titleRe = [regex]::Match($chunk, '(?is)<b[^>]*>(.*?)</b>')
        }
        if (-not $titleRe.Success) {
            $titleRe = [regex]::Match($chunk, '(?is)<option[^>]*>(.*?)</option>')
        }
        
        if ($titleRe.Success) {
            $name = ([regex]::Replace($titleRe.Groups[1].Value, '<[^>]+>', ' ') -replace '\s+', ' ').Trim()
        } else {
            $name = ([regex]::Replace($cleanChunk, '<[^>]+>', ' ') -replace '\s+', ' ').Trim()
        }
        
        if ($name -and -not ($name -match '^\d+$')) {
            $out += [PSCustomObject]@{ id = $id; name = $name }
        }
    }
    $out | Group-Object id | ForEach-Object { $_.Group[0] }
}

$uri = [Uri]$BaseUrl
$hostName = $uri.Host
$port = if ($uri.IsDefaultPort) { if ($uri.Scheme -eq "https") { 443 } else { 80 } } else { $uri.Port }

$report = [ordered]@{
    timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    baseUrl = $BaseUrl
    dates = $Dates
    tcp = [ordered]@{
        host = $hostName
        port = $port
        open = Test-TcpPort -HostName $hostName -Port $port
    }
    ping = $null
    loket = @()
    login = $null
    stock = @()
    pilihmenu = @()
    detailProbe = @()
}

$ping = Invoke-Check "$BaseUrl/cekorder.php?ping=1"
$report.ping = [PSCustomObject]@{
    ok = $ping.ok
    status = $ping.status
    preview = Short-Body $ping.body 300
    json = Parse-Json $ping.body
}

foreach ($loket in $Lokets) {
    $r = Invoke-Check "$BaseUrl/cekorder.php?loket=$loket"
    $j = Parse-Json $r.body
    $schedules = @($j.data.schedules)
    $firstMenu = $null
    $firstDetail = $null
    if ($schedules.Count) {
        $firstMenu = $schedules[0].menu_name
        $firstDetail = @(
            $schedules[0].carbo_name
            $schedules[0].main_name
            $schedules[0].soup_name
            $schedules[0].option1_name
            $schedules[0].option2_name
            $schedules[0].option3_name
            $schedules[0].fruit_name
            $schedules[0].additional_name
        ) | Where-Object { $_ }
        $firstDetail = $firstDetail -join " | "
    }
    $report.loket += [PSCustomObject]@{
        loket = $loket
        ok = $r.ok
        status = $r.status
        apiStatus = $j.status
        scheduleCount = $j.data.schedule_count
        firstMenu = $firstMenu
        firstDetail = $firstDetail
        preview = if ($j) { $null } else { Short-Body $r.body 500 }
    }
}

if ($GenId -and $Password) {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    try {
        $login = Invoke-WebRequest -Uri "$BaseUrl/auth/login" -Method Post -Body @{ identity = $GenId; password = $Password } -WebSession $session -TimeoutSec 12 -UseBasicParsing
        $report.login = [PSCustomObject]@{
            ok = $true
            status = [int]$login.StatusCode
            cookieCount = $session.Cookies.GetCookies([Uri]$BaseUrl).Count
        }
    } catch {
        $report.login = [PSCustomObject]@{ ok = $false; error = $_.Exception.Message }
    }

    foreach ($day in $Dates) {
        foreach ($mealId in $MealIds) {
            $stock = Invoke-Check "$BaseUrl/order/get_stock_data?date=$day&schedule_meal_id=$mealId" $session
            $stockJson = Parse-Json $stock.body
            $stockRows = @($stockJson.data)
            $firstIds = @($stockRows | Select-Object -First 3 | ForEach-Object { $_.schedule_menu_id })
            $report.stock += [PSCustomObject]@{
                date = $day
                mealId = $mealId
                ok = $stock.ok
                status = $stock.status
                success = $stockJson.success
                rowCount = $stockRows.Count
                firstRows = $stockRows | Select-Object -First 10 schedule_menu_id, menu_name, main_name, carbo_name, qty_balance
                preview = if ($stockJson) { $null } else { Short-Body $stock.body 600 }
            }

            $pageUrls = @(
                "$BaseUrl/order/pilihmenu?xtanggal=$day&xjadwal=$mealId",
                "$BaseUrl/order/pilihmenu?xfor_date=$day&xjm=$mealId",
                "$BaseUrl/order/pilihmenu?xtanggal=$day&xjadwal=$mealId&xfor_date=$day&xjm=$mealId"
            )
            foreach ($url in $pageUrls) {
                $page = Invoke-Check $url $session
                $names = Menu-Names-From-Html $page.body
                $report.pilihmenu += [PSCustomObject]@{
                    date = $day
                    mealId = $mealId
                    url = $url
                    ok = $page.ok
                    status = $page.status
                    length = $page.length
                    parsedNameCount = @($names).Count
                    parsedNames = $names
                    hasMenuTitle = $page.body -match "menu-title"
                    hasMenuInfo = $page.body -match "menu-info"
                    hasMenuItemName = $page.body -match "menu-item-name"
                    preview = Short-Body $page.body 800
                }
            }

            foreach ($id in $firstIds) {
                foreach ($url in @(
                    "$BaseUrl/order/get_menu_detail?schedule_menu_id=$id",
                    "$BaseUrl/order/get_menu_detail?id=$id",
                    "$BaseUrl/order/get_menu_data?schedule_menu_id=$id",
                    "$BaseUrl/order/get_menu_data?id=$id",
                    "$BaseUrl/order/menu_detail?schedule_menu_id=$id",
                    "$BaseUrl/order/menu_detail?id=$id",
                    "$BaseUrl/order/detail_menu?schedule_menu_id=$id",
                    "$BaseUrl/order/detail_menu?id=$id"
                )) {
                    $probe = Invoke-Check $url $session
                    $json = Parse-Json $probe.body
                    $report.detailProbe += [PSCustomObject]@{
                        date = $day
                        mealId = $mealId
                        scheduleMenuId = $id
                        url = $url
                        ok = $probe.ok
                        status = $probe.status
                        length = $probe.length
                        jsonKeys = if ($json) { $json.PSObject.Properties.Name } else { @() }
                        preview = Short-Body $probe.body 500
                    }
                }
            }
        }
    }
} else {
    $report.login = [PSCustomObject]@{ skipped = $true; reason = "Run with -GenId and -Password to test /order endpoints." }
}

$json = $report | ConvertTo-Json -Depth 8
$json | Set-Content -Path $OutFile -Encoding UTF8
$json
Write-Host ""
Write-Host "Saved: $OutFile"
