import 'package:flutter/material.dart';
import '../design/serv60_theme.dart';

class MapPoint {
  const MapPoint(
      {required this.id,
      required this.latitude,
      required this.longitude,
      required this.label,
      this.isCustomer = false,
      this.isTruck = false});
  final String id;
  final double latitude, longitude;
  final String label;
  final bool isCustomer, isTruck;
}

/// Lightweight map plane for nearby tows — works in tests without Google Maps keys.
class RoadsideMapCanvas extends StatelessWidget {
  const RoadsideMapCanvas(
      {super.key,
      required this.centerLat,
      required this.centerLon,
      required this.points,
      this.onTap});

  final double centerLat, centerLon;
  final List<MapPoint> points;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => SizedBox(
      height: 220,
      width: double.infinity,
      child: GestureDetector(
          onTap: onTap,
          child: DecoratedBox(
              decoration: BoxDecoration(
                  color: const Color(0xffe8ebe4),
                  border: Border.all(color: serv60Border)),
              child: CustomPaint(
                  painter: _RoadsideMapPainter(
                      centerLat: centerLat,
                      centerLon: centerLon,
                      points: points),
                  child: const SizedBox.expand()))));
}

class _RoadsideMapPainter extends CustomPainter {
  _RoadsideMapPainter(
      {required this.centerLat, required this.centerLon, required this.points});
  final double centerLat, centerLon;
  final List<MapPoint> points;

  @override
  void paint(Canvas canvas, Size size) {
    final grid = Paint()
      ..color = const Color(0xffd2d6cf)
      ..strokeWidth = 1;
    for (var i = 1; i < 6; i++) {
      final x = size.width * i / 6;
      final y = size.height * i / 6;
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), grid);
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }
    const span = 0.06;
    Offset project(double lat, double lon) {
      final x = ((lon - (centerLon - span / 2)) / span).clamp(0.05, 0.95);
      final y = ((centerLat + span / 2) - lat) / span;
      return Offset(size.width * x, size.height * y.clamp(0.05, 0.95));
    }

    for (final point in points) {
      final at = project(point.latitude, point.longitude);
      final color = point.isCustomer
          ? serv60Orange
          : point.isTruck
              ? serv60Ink
              : serv60AccentInk;
      canvas.drawCircle(at, point.isCustomer ? 10 : 8, Paint()..color = color);
      if (point.isTruck) {
        canvas.drawCircle(
            at,
            14,
            Paint()
              ..color = color.withOpacity(0.2)
              ..style = PaintingStyle.stroke
              ..strokeWidth = 2);
      }
    }
  }

  @override
  bool shouldRepaint(covariant _RoadsideMapPainter old) =>
      old.centerLat != centerLat ||
      old.centerLon != centerLon ||
      old.points != points;
}
