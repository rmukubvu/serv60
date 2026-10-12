import 'package:flutter/material.dart';

// The public site's paper, ink and orange are the core Serv60 brand tokens.
const serv60Paper = Color(0xfff3f1eb);
const serv60Ink = Color(0xff111718);
const serv60Muted = Color(0xff656b67);
const serv60Orange = Color(0xffef7145);
// Use darker orange for small text and checkboxes to maintain contrast.
const serv60AccentInk = Color(0xff963d23);
const serv60Border = Color(0xffd0d2c9);
const serv60Surface = Color(0xfffaf9f5);
const serv60Selected = Color(0xfff4e8df);
const serv60Error = Color(0xffa12e26);

ThemeData serv60Theme() {
  const shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.all(Radius.circular(3)));
  final base = ThemeData(
      useMaterial3: true,
      fontFamily: 'Arial',
      fontFamilyFallback: const ['Helvetica', 'sans-serif'],
      colorScheme: const ColorScheme.light(
        primary: serv60AccentInk,
        onPrimary: Colors.white,
        primaryContainer: serv60Selected,
        onPrimaryContainer: serv60Ink,
        secondary: serv60AccentInk,
        onSecondary: Colors.white,
        surface: serv60Paper,
        onSurface: serv60Ink,
        onSurfaceVariant: serv60Muted,
        outline: serv60Border,
        outlineVariant: serv60Border,
        error: serv60Error,
        onError: Colors.white,
        surfaceTint: Colors.transparent,
      ));
  return base.copyWith(
    scaffoldBackgroundColor: serv60Paper,
    textTheme: base.textTheme.copyWith(
      headlineLarge: const TextStyle(
          fontSize: 38,
          height: 1.08,
          fontWeight: FontWeight.w500,
          letterSpacing: -1.8,
          color: serv60Ink),
      titleMedium: const TextStyle(
          fontSize: 16,
          height: 1.35,
          fontWeight: FontWeight.w500,
          color: serv60Ink),
      bodyMedium: const TextStyle(fontSize: 14, height: 1.5, color: serv60Ink),
      bodySmall: const TextStyle(fontSize: 12, height: 1.5, color: serv60Muted),
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: serv60Paper,
      foregroundColor: serv60Ink,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleSpacing: 24,
      toolbarHeight: 76,
      shape: Border(bottom: BorderSide(color: serv60Border)),
    ),
    cardTheme: const CardThemeData(
      elevation: 0,
      margin: EdgeInsets.zero,
      color: serv60Surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(3)),
          side: BorderSide(color: serv60Border)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: serv60Orange,
        foregroundColor: serv60Ink,
        disabledBackgroundColor: serv60Border,
        disabledForegroundColor: serv60Muted,
        minimumSize: const Size(0, 50),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
        shape: shape,
        elevation: 0,
        textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: serv60Ink,
        minimumSize: const Size(0, 50),
        side: const BorderSide(color: serv60Border),
        shape: shape,
        textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
      ),
    ),
    checkboxTheme: CheckboxThemeData(
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(2))),
      side: const BorderSide(color: serv60Muted, width: 1.5),
      fillColor: WidgetStateProperty.resolveWith((states) =>
          states.contains(WidgetState.selected)
              ? serv60AccentInk
              : Colors.transparent),
      checkColor: const WidgetStatePropertyAll(Colors.white),
    ),
    listTileTheme: const ListTileThemeData(
      contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      iconColor: serv60AccentInk,
      textColor: serv60Ink,
      titleTextStyle: TextStyle(
          fontSize: 16, fontWeight: FontWeight.w500, color: serv60Ink),
      subtitleTextStyle:
          TextStyle(fontSize: 12, height: 1.6, color: serv60Muted),
    ),
    inputDecorationTheme: const InputDecorationTheme(
      filled: true,
      fillColor: serv60Surface,
      contentPadding: EdgeInsets.all(16),
      labelStyle: TextStyle(color: serv60Muted),
      hintStyle: TextStyle(color: serv60Muted),
      border: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(3))),
      enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(3)),
          borderSide: BorderSide(color: serv60Border)),
      focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(3)),
          borderSide: BorderSide(color: serv60AccentInk, width: 2)),
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: 72,
      backgroundColor: serv60Paper,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      indicatorColor: Colors.transparent,
      iconTheme: WidgetStateProperty.resolveWith((states) => IconThemeData(
          color: states.contains(WidgetState.selected)
              ? serv60AccentInk
              : serv60Muted,
          size: 23)),
      labelTextStyle: WidgetStateProperty.resolveWith((states) => TextStyle(
          color: states.contains(WidgetState.selected)
              ? serv60AccentInk
              : serv60Muted,
          fontSize: 12,
          fontWeight: states.contains(WidgetState.selected)
              ? FontWeight.w600
              : FontWeight.w400)),
    ),
    dividerTheme: const DividerThemeData(color: serv60Border, thickness: 1),
    snackBarTheme: const SnackBarThemeData(
        backgroundColor: serv60Ink,
        contentTextStyle: TextStyle(color: serv60Paper)),
  );
}

class Serv60Wordmark extends StatelessWidget {
  const Serv60Wordmark({super.key});

  @override
  Widget build(BuildContext context) => Semantics(
      label: 'Serv60',
      excludeSemantics: true,
      child: Text.rich(
          TextSpan(children: [
            const TextSpan(
                text: 'serv', style: TextStyle(fontWeight: FontWeight.w700)),
            const TextSpan(
                text: '60', style: TextStyle(fontWeight: FontWeight.w400)),
            WidgetSpan(
                alignment: PlaceholderAlignment.baseline,
                baseline: TextBaseline.alphabetic,
                child: Container(
                    margin: const EdgeInsets.only(left: 5),
                    width: 6,
                    height: 6,
                    decoration: const BoxDecoration(
                        color: serv60Orange, shape: BoxShape.circle))),
          ]),
          style: const TextStyle(
              fontSize: 34, letterSpacing: -2, color: serv60Ink)));
}
