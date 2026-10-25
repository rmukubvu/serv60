import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:serv60_mobile/src/roadside/roadside_map.dart';

const customer = MapPoint(
    id: 'me',
    latitude: -33.925,
    longitude: 18.424,
    label: 'You',
    isCustomer: true);
const truck = MapPoint(
    id: 'p1',
    latitude: -33.92,
    longitude: 18.42,
    label: 'City Bowl Tow',
    isTruck: true);
const destination = MapPoint(
    id: 'home',
    latitude: -33.93,
    longitude: 18.43,
    label: 'Home',
    isDestination: true);
const other = MapPoint(id: 'x', latitude: -33.92, longitude: 18.43, label: 'X');

Widget map(RoadsideMapCanvas canvas) =>
    MaterialApp(home: Scaffold(body: canvas));

MapCamera camera(WidgetTester tester) =>
    tester.widget<FlutterMap>(find.byType(FlutterMap)).mapController!.camera;

void main() {
  testWidgets('draws every kind of marker and reports taps both ways',
      (tester) async {
    var taps = 0;
    double? tappedLat, tappedLon;
    await tester.pumpWidget(map(RoadsideMapCanvas(
        centerLat: -33.925,
        centerLon: 18.424,
        points: const [customer, truck, destination, other],
        onTap: () => taps++,
        onTapAt: (lat, lon) {
          tappedLat = lat;
          tappedLon = lon;
        })));
    await tester.pump();
    expect(tester.getSize(find.byType(RoadsideMapCanvas)).height, 220);
    expect(find.byIcon(Icons.location_on), findsOneWidget);
    expect(find.byIcon(Icons.local_shipping), findsOneWidget);
    expect(find.text('City Bowl Tow'), findsOneWidget);
    expect(find.byIcon(Icons.flag), findsOneWidget);
    expect(customer.position.latitude, -33.925);

    // The OSM credit opens and closes from the attribution button.
    expect(find.textContaining('OpenStreetMap contributors'), findsOneWidget);
    await tester.tap(find.byIcon(Icons.info_outlined));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.cancel_outlined), findsOneWidget);
    await tester.tap(find.byIcon(Icons.cancel_outlined));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.info_outlined), findsOneWidget);

    await tester.tapAt(tester.getCenter(find.byType(FlutterMap)));
    // A single tap is confirmed once the double-tap window has passed.
    await tester.pump(const Duration(milliseconds: 300));
    expect(taps, 1);
    expect(tappedLat, closeTo(-33.925, 0.0005));
    expect(tappedLon, closeTo(18.424, 0.0005));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('follows the centre only once it leaves the view',
      (tester) async {
    Widget at(double lat, double lon) => map(RoadsideMapCanvas(
        centerLat: lat, centerLon: lon, points: const [customer]));
    await tester.pumpWidget(at(-33.925, 18.424));
    await tester.pump();
    expect(camera(tester).zoom, 13);
    // A nudge within the view leaves the camera where it is.
    await tester.pumpWidget(at(-33.926, 18.425));
    await tester.pump();
    expect(camera(tester).center.latitude, closeTo(-33.925, 1e-6));
    // A jump across town recentres at the same zoom.
    await tester.pumpWidget(at(-33.80, 18.60));
    await tester.pump();
    expect(camera(tester).center.latitude, closeTo(-33.80, 1e-6));
    expect(camera(tester).center.longitude, closeTo(18.60, 1e-6));
    expect(camera(tester).zoom, 13);
    await tester.pumpWidget(const SizedBox());
  });
}
