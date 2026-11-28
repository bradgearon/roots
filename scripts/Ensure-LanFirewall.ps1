# Only the explicitly requested LAN bridge is opened. Never elevate the host.
[CmdletBinding()]
param([switch]$Apply, [ValidateSet(8221, 8223, 8233)][int]$Port = 8223)

$ErrorActionPreference = 'Stop'
$ruleName = "Wds.Roots.DesktopLan.Tcp$Port"
$displayName = "Roots Desktop LAN (TCP $Port)"

function Test-RootsRule {
    param([string]$PolicyStore = 'ActiveStore')
    $rule = Get-NetFirewallRule -PolicyStore $PolicyStore -Name $ruleName -ErrorAction SilentlyContinue
    if (-not $rule) { return $false }
    $portFilter = $rule | Get-NetFirewallPortFilter
    $address = $rule | Get-NetFirewallAddressFilter
    $program = $rule | Get-NetFirewallApplicationFilter
    $service = $rule | Get-NetFirewallServiceFilter
    return (
        [string]$rule.Enabled -eq 'True' -and
        [string]$rule.Direction -eq 'Inbound' -and
        [string]$rule.Action -eq 'Allow' -and
        [string]$rule.Profile -eq 'Private' -and
        [string]$rule.EdgeTraversalPolicy -eq 'Block' -and
        [string]$portFilter.Protocol -in @('TCP', '6') -and
        @($portFilter.LocalPort).Count -eq 1 -and
        [string]$portFilter.LocalPort -eq [string]$Port -and
        @($address.RemoteAddress).Count -eq 1 -and
        [string]$address.RemoteAddress -eq 'LocalSubnet' -and
        [string]$address.LocalAddress -eq 'Any' -and
        [string]$program.Program -eq 'Any' -and
        [string]$service.Service -eq 'Any'
    )
}

try {
    if ($Apply) {
        $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
        if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
            throw 'Administrator approval is required to install the firewall rule.'
        }
        if (-not (Test-RootsRule)) {
            # Replace only the rule owned by this launcher, never other rules.
            $existing = Get-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName -ErrorAction SilentlyContinue
            if ($existing) {
                Remove-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName
            }
            New-NetFirewallRule -PolicyStore PersistentStore -Name $ruleName -DisplayName $displayName -Description 'Roots desktop TLS bridge. Private profile and local subnet only.' -Enabled True -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -Profile Private -RemoteAddress LocalSubnet -EdgeTraversalPolicy Block | Out-Null
            if (-not (Test-RootsRule)) {
                throw 'The rule was created but is not effective in ActiveStore. Check Windows Firewall or managed policy.'
            }
        }
        exit 0
    }

    if (Test-RootsRule) {
        Write-Host "Roots LAN firewall rule is already configured (private local subnet, TCP $Port)."
    } else {
        Write-Host ''
        Write-Host "Roots LAN mode accepts TLS connections from your phone on TCP $Port."
        Write-Host "Allow inbound TCP $Port from the local subnet on Private networks only?"
        Write-Host 'Windows may prompt for administrator approval. No public-network rule will be added.'
        $answer = Read-Host 'Add Windows Firewall rule [y/N]'
        if ($answer -notmatch '^(y|yes)$') {
            Write-Host 'Firewall unchanged. LAN mode may be blocked by existing firewall policy.'
            exit 0
        }
        $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
        if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
            # Run in a child process so that the apply branch can return an exit code.
            $child = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-Apply', '-Port', [string]$Port) -Wait -PassThru
        } else {
            $child = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-Apply', '-Port', [string]$Port) -Verb RunAs -Wait -PassThru
        }
        if ($child.ExitCode -ne 0) { throw 'Firewall rule installation failed.' }
        Write-Host "Roots LAN firewall rule installed (private local subnet, TCP $Port)."
    }

    # Report the profile without changing it. Public Wi-Fi remains blocked.
    $profiles = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue)
    if ($profiles.Count -gt 0 -and -not ($profiles | Where-Object NetworkCategory -eq 'Private')) {
        Write-Warning 'No Private network profile is currently active. The Roots rule does not allow traffic on Public or Domain profiles.'
    }
    exit 0
} catch {
    Write-Warning ('Roots firewall setup: ' + $_.Exception.Message)
    exit 1
}
