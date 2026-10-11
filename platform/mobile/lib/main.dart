import 'package:flutter/material.dart';
import 'src/booking/booking_page.dart';
void main() => runApp(const Serv60App());
class Serv60App extends StatelessWidget {
  const Serv60App({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(title: 'Serv60', debugShowCheckedModeBanner: false, theme: ThemeData(brightness: Brightness.dark, colorSchemeSeed: const Color(0xffc7f464), scaffoldBackgroundColor: const Color(0xff0a0d12), useMaterial3: true), home: const BookingPage());
}
