// GioVangProvider/build.gradle.kts
// Tăng version mỗi khi sửa code provider

version = 1

cloudstream {
    language    = "vi"
    description = "Xem bóng đá trực tiếp từ giovang.vin — World Cup, V-League, Premier League..."
    authors     = listOf("GioVang Dev")
    status      = 1          // 0=Down 1=Ok 2=Slow 3=Beta
    tvTypes     = listOf("Live")
    iconUrl     = "https://www.google.com/s2/favicons?domain=giovang.vin&sz=%size%"
}
