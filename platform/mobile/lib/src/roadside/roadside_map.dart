import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../design/serv60_theme.dart';

class MapPoint {
  const MapPoint(
      {required this.id,
      required this.latitude,
      required this.longitude,
      required this.label,
      this.isCustomer = false,
      this.isTruck = false,
      this.isDestination = false});
  final String id;
  final double latitude, longitude;
  final String label;
  final bool isCustomer, isTruck, isDestination;
  LatLng get position => LatLng(latitude, longitude);
}

/// Receives the coordinates under a tap on the map.
typedef MapTapCallback = void Function(double latitude, double longitude);

/// OpenStreetMap view of the breakdown and the tow trucks around it.
///
/// Tiles come straight from the public OSM servers; one that fails to load
/// (offline, or in widget tests without network) is left blank rather than
/// raised as an error. The customer's pin is orange, trucks are dark with
/// their name underneath, and a destination, when the page passes one, is
/// flagged.
class RoadsideMapCanvas extends StatefulWidget {
  const RoadsideMapCanvas(
      {super.key,
      required this.centerLat,
      required this.centerLon,
      required this.points,
      this.onTap,
      this.onTapAt,
      this.tileProvider});

  final double centerLat, centerLon;
  final List<MapPoint> points;

  /// Fires on any tap, before [onTapAt].
  final VoidCallback? onTap;

  /// Fires with the latitude and longitude under the tap.
  final MapTapCallback? onTapAt;

  /// Replaces the OpenStreetMap tile source, e.g. with an offline fixture.
  final TileProvider? tileProvider;

  static const double height = 220;
  static const double zoom = 13;
  static const tileUrl = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
  static const userAgentPackageName = 'com.serv60.mobile';
  static final copyright = Uri.parse('https://www.openstreetmap.org/copyright');

  /// The map sits inside a scrolling page, so one finger scrolls the page and
  /// two fingers (or a double tap) move and zoom the map.
  static const interactions = InteractiveFlag.pinchZoom |
      InteractiveFlag.pinchMove |
      InteractiveFlag.doubleTapZoom |
      InteractiveFlag.doubleTapDragZoom |
      InteractiveFlag.scrollWheelZoom;

  @override
  State<RoadsideMapCanvas> createState() => _RoadsideMapCanvasState();
}

class _RoadsideMapCanvasState extends State<RoadsideMapCanvas> {
  final controller = MapController();
  bool ready = false;

  // One provider for the widget's lifetime; the tile layer closes it when it
  // is disposed. flutter_map's built-in disk cache needs path_provider and an
  // isolate, neither of which exists in widget tests, so tiles are only kept
  // in Flutter's in-memory image cache.
  late final TileProvider tiles = widget.tileProvider ??
      NetworkTileProvider(
          silenceExceptions: true,
          cachingProvider: const DisabledMapCachingProvider());

  LatLng get center => LatLng(widget.centerLat, widget.centerLon);

  @override
  void didUpdateWidget(RoadsideMapCanvas old) {
    super.didUpdateWidget(old);
    final moved =
        old.centerLat != widget.centerLat || old.centerLon != widget.centerLon;
    // Follow the pin only once it leaves the view, so dropping it near an
    // edge does not yank the map around under the customer's finger.
    if (moved && ready && !controller.camera.visibleBounds.contains(center)) {
      controller.move(center, controller.camera.zoom);
    }
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  void tapped(TapPosition _, LatLng point) {
    widget.onTap?.call();
    widget.onTapAt?.call(point.latitude, point.longitude);
  }

  @override
  Widget build(BuildContext context) => Container(
      height: RoadsideMapCanvas.height,
      width: double.infinity,
      foregroundDecoration:
          BoxDecoration(border: Border.all(color: serv60Border)),
      child: FlutterMap(
          mapController: controller,
          options: MapOptions(
              initialCenter: center,
              initialZoom: RoadsideMapCanvas.zoom,
              backgroundColor: const Color(0xffe8ebe4),
              interactionOptions: const InteractionOptions(
                  flags: RoadsideMapCanvas.interactions),
              onMapReady: () => ready = true,
              onTap: tapped),
          children: [
            TileLayer(
                urlTemplate: RoadsideMapCanvas.tileUrl,
                userAgentPackageName: RoadsideMapCanvas.userAgentPackageName,
                tileProvider: tiles),
            MarkerLayer(
                markers: [for (final point in widget.points) marker(point)]),
            RichAttributionWidget(
                showFlutterMapAttribution: false,
                popupInitialDisplayDuration: Duration.zero,
                attributions: [
                  TextSourceAttribution('OpenStreetMap contributors',
                      onTap: () => launchUrl(RoadsideMapCanvas.copyright,
                          mode: LaunchMode.externalApplication)),
                ]),
          ]));

  Marker marker(MapPoint point) {
    if (point.isCustomer) {
      // The pin's tip sits on the breakdown.
      return Marker(
          point: point.position,
          width: 40,
          height: 40,
          alignment: Alignment.topCenter,
          child: const Icon(Icons.location_on, size: 40, color: serv60Orange));
    }
    if (point.isTruck) return truckMarker(point);
    if (point.isDestination) {
      return Marker(
          point: point.position,
          width: 32,
          height: 32,
          alignment: Alignment.topRight,
          child: const Icon(Icons.flag, size: 32, color: serv60AccentInk));
    }
    return Marker(
        point: point.position,
        width: 16,
        height: 16,
        child: const DecoratedBox(
            decoration:
                BoxDecoration(color: serv60AccentInk, shape: BoxShape.circle)));
  }

  /// A dark disc on the truck's position with its name hanging below.
  Marker truckMarker(MapPoint point) => Marker(
      point: point.position,
      width: 120,
      height: 50,
      alignment: Marker.computePixelAlignment(
          width: 120, height: 50, left: 60, top: 14),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
                color: serv60Ink,
                shape: BoxShape.circle,
                border: Border.all(color: serv60Paper, width: 2)),
            child:
                const Icon(Icons.local_shipping, size: 14, color: serv60Paper)),
        const SizedBox(height: 2),
        Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
                color: serv60Ink, borderRadius: BorderRadius.circular(3)),
            child: Text(point.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    color: serv60Paper,
                    fontSize: 10,
                    fontWeight: FontWeight.w600))),
      ]));
}
