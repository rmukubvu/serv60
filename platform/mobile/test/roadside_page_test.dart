import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart';
import 'package:http/testing.dart';
import 'package:serv60_mobile/src/design/serv60_theme.dart';
import 'package:serv60_mobile/src/roadside/roadside_page.dart';
import 'package:serv60_mobile/src/roadside/roadside_repository.dart';

void main() {
  testWidgets('loads nearby trucks, quotes a tow and can cancel', (tester) async {
    var cancelled = false;
    final repository = RoadsideRepository(
      client: MockClient((request) async {
        if (request.url.path.contains('/nearby')) {
          return Response(
              '[{"partnerId":"p1","name":"City Bowl Tow","latitude":-33.92,"longitude":18.42,"etaMinutes":10,"distanceKm":1.2}]',
              200);
        }
        if (request.url.path.endsWith('/cancel')) {
          cancelled = true;
          return Response(
              '{"id":"incident-1","customerId":"c","status":"CANCELLED","location":"Pin","description":"Flat","rideHomeRequested":false,"quotes":[],"createdAt":"2026-10-12T08:00:00Z"}',
              200);
        }
        return Response(
            '{"id":"incident-1","customerId":"c","status":"QUOTED","location":"Pin","description":"Flat","latitude":-33.9,"longitude":18.4,"rideHomeRequested":false,"quotes":[{"id":"quote-1","partnerId":"p1","partnerName":"City Bowl Tow","towAmountMinor":52000,"rideAmountMinor":0,"totalAmountMinor":52000,"currency":"ZAR","etaMinutes":12,"distanceKm":4.2,"expiresAt":"2035-01-01T12:00:00Z"}],"createdAt":"2026-10-12T08:00:00Z"}',
            201);
      }),
    );

    await tester.pumpWidget(MaterialApp(
        theme: serv60Theme(),
        home: Scaffold(
            body: RoadsidePage(repository: repository, demoEnabled: true))));
    await tester.pumpAndSettle();

    expect(find.text('Roadside help'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Request tow quotes'), 300,
        scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('Request tow quotes'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Status · QUOTED'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Cancel request'), 300,
        scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('Cancel request'));
    await tester.pumpAndSettle();

    expect(cancelled, isTrue);
    expect(find.textContaining('CANCELLED'), findsOneWidget);
  });
}
