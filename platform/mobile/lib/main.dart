import 'package:flutter/material.dart';
import 'src/booking/booking_page.dart';
import 'src/roadside/roadside_page.dart';

void main() => runApp(const Serv60App());

class Serv60App extends StatelessWidget {
  const Serv60App({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
      title: 'Serv60',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
          brightness: Brightness.dark,
          colorSchemeSeed: const Color(0xffc7f464),
          scaffoldBackgroundColor: const Color(0xff0a0d12),
          useMaterial3: true),
      home: const Serv60Home());
}

class Serv60Home extends StatefulWidget {
  const Serv60Home({super.key});

  @override
  State<Serv60Home> createState() => _Serv60HomeState();
}

class _Serv60HomeState extends State<Serv60Home> {
  int selectedIndex = 0;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: IndexedStack(
            index: selectedIndex,
            children: const [BookingPage(), RoadsidePage()]),
        bottomNavigationBar: NavigationBar(
            selectedIndex: selectedIndex,
            onDestinationSelected: (index) =>
                setState(() => selectedIndex = index),
            destinations: const [
              NavigationDestination(
                  icon: Icon(Icons.event_available), label: 'Book'),
              NavigationDestination(
                  icon: Icon(Icons.car_repair), label: 'Roadside')
            ]),
      );
}
