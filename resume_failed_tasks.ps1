$database = "taskpilot.db"
$rows = & sqlite3 $database "SELECT request FROM tasks WHERE status='FAILED';"
foreach ($row in $rows) {
    $request = $row.Trim()
    if ($request) {
        Write-Host "Resubmitting task: $request"
    $headers = @{ "Content-Type" = "application/json" }
        Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/tasks -Headers $headers -Body "{\"request\":\"$request\"}"
    }
}
