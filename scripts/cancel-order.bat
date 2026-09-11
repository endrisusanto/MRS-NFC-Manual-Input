@echo off
if exist "%~dp0cancel_order.cjs" (
    node "%~dp0cancel_order.cjs" %*
) else if exist "%~dp0cancel_order.js" (
    node "%~dp0cancel_order.js" %*
) else (
    node cancel_order.cjs %*
)
