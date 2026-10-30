package de.ernaehrung.demo.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Forest=Color(0xFF174E38)
val Sage=Color(0xFFE9EFDE)
val Ink=Color(0xFF203D31)
val Muted=Color(0xFF5C6B63)
val Warm=Color(0xFFF7F8F2)
val Line=Color(0xFFDEE4D7)
val Blue=Color(0xFFE9F0F8)
val Purple=Color(0xFFEFEAF8)
val Amber=Color(0xFFFFF2D9)

@Composable fun DemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Forest,onPrimary=Color.White,primaryContainer=Sage,onPrimaryContainer=Ink,
        secondary=Color(0xFF315B80),secondaryContainer=Blue,tertiary=Color(0xFF674F85),tertiaryContainer=Purple,
        background=Warm,onBackground=Ink,surface=Color.White,onSurface=Ink,onSurfaceVariant=Muted,surfaceVariant=Sage,outline=Muted,outlineVariant=Line),
        typography=Typography(
            headlineLarge=TextStyle(fontSize=30.sp,lineHeight=35.sp,fontWeight=FontWeight.SemiBold),
            headlineMedium=TextStyle(fontSize=26.sp,lineHeight=32.sp,fontWeight=FontWeight.SemiBold),
            titleLarge=TextStyle(fontSize=20.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold),
            titleMedium=TextStyle(fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold),
            bodyLarge=TextStyle(fontSize=15.sp,lineHeight=22.sp),bodyMedium=TextStyle(fontSize=13.sp,lineHeight=19.sp),
            labelLarge=TextStyle(fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium),
            labelSmall=TextStyle(fontSize=11.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium)),content=content)
}
