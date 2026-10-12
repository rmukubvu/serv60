import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:serv60_mobile/src/booking/booking_page.dart';
import 'package:serv60_mobile/src/design/serv60_theme.dart';

void main() {
  test('brand text and actions have accessible contrast', () {
    double contrast(Color foreground, Color background) {
      final a = foreground.computeLuminance();
      final b = background.computeLuminance();
      return a > b ? (a + .05) / (b + .05) : (b + .05) / (a + .05);
    }

    expect(contrast(serv60Muted, serv60Paper), greaterThanOrEqualTo(4.5));
    expect(contrast(serv60Ink, serv60Orange), greaterThanOrEqualTo(4.5));
    expect(
        contrast(serv60AccentInk, serv60Selected), greaterThanOrEqualTo(4.5));
    expect(contrast(Colors.white, serv60AccentInk), greaterThanOrEqualTo(4.5));
    expect(contrast(serv60Error, serv60Paper), greaterThanOrEqualTo(4.5));
  });

  testWidgets('booking remains usable on a narrow screen with large text',
      (tester) async {
    tester.view.physicalSize = const Size(320, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      theme: serv60Theme(),
      builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: const TextScaler.linear(1.5)),
          child: child!),
      home: const BookingPage(),
    ));
    expect(find.bySemanticsLabel('Serv60'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Barber'), 150);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Barber'));
    await tester.pump();
    await tester.scrollUntilVisible(find.text('Private workspace'), 150);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Private workspace'));
    await tester.pump();
    expect(
        tester
            .widget<CheckboxListTile>(
                find.widgetWithText(CheckboxListTile, 'Private workspace'))
            .value,
        isTrue);
    await tester.scrollUntilVisible(find.text('Barber'), -150);
    await tester.pumpAndSettle();
    expect(
        tester
            .widget<CheckboxListTile>(
                find.widgetWithText(CheckboxListTile, 'Barber'))
            .value,
        isFalse);
    await tester.scrollUntilVisible(find.text('Check live times'), 150);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
}
