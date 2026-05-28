param(
    [int] $Port = 8080,
    [string] $RuleName = "AudioScholar Backend 8080"
)

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Error "Run this script from an Administrator PowerShell window."
    exit 1
}

$existingRule = Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
if ($null -eq $existingRule) {
    New-NetFirewallRule `
        -DisplayName $RuleName `
        -Direction Inbound `
        -Action Allow `
        -Protocol TCP `
        -LocalPort $Port `
        -Profile Private,Public | Out-Null

    Write-Host "Created inbound firewall rule '$RuleName' for TCP port $Port."
} else {
    Write-Host "Firewall rule '$RuleName' already exists."
}

$ipAddress = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object {
        $_.IPAddress -notlike "127.*" -and
        $_.IPAddress -notlike "169.254.*" -and
        $_.PrefixOrigin -ne "WellKnown"
    } |
    Select-Object -ExpandProperty IPAddress -First 1

if ($ipAddress) {
    Write-Host "Backend test URL for your phone: http://$ipAddress`:$Port/api/users/me"
} else {
    Write-Warning "Could not detect a LAN IPv4 address. Use ipconfig to find your PC IPv4 address."
}
