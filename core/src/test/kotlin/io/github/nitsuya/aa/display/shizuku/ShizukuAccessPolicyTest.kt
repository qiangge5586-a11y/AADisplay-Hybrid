package io.github.nitsuya.aa.display.shizuku
fun main() {
    check(ShizukuAccessPolicy.resolve(false,false,null) == ShizukuAccessState.SERVICE_UNAVAILABLE)
    check(ShizukuAccessPolicy.resolve(true,false,2000) == ShizukuAccessState.PERMISSION_REQUIRED)
    check(ShizukuAccessPolicy.resolve(true,true,2000) == ShizukuAccessState.GRANTED_SHELL)
    check(ShizukuAccessPolicy.resolve(true,true,0) == ShizukuAccessState.GRANTED_ROOT)
    check(ShizukuAccessPolicy.resolve(true,true,1000) == ShizukuAccessState.SERVICE_UNAVAILABLE)
    println("ShizukuAccessPolicy checks passed")
}
