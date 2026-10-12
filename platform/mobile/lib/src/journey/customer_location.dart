import 'package:geolocator/geolocator.dart';
import 'journey_repository.dart';

abstract interface class CustomerLocation {
  Future<({double latitude, double longitude})> current();
}

class DeviceCustomerLocation implements CustomerLocation {
  @override
  Future<({double latitude, double longitude})> current() async {
    if (!await Geolocator.isLocationServiceEnabled())
      throw JourneyException('Turn on location services, then try again.');
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied)
      permission = await Geolocator.requestPermission();
    if (permission == LocationPermission.denied ||
        permission == LocationPermission.deniedForever) {
      throw JourneyException(
          'Location access is off. Enable it in your browser or device settings to find hubs near you.');
    }
    final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
            accuracy: LocationAccuracy.medium,
            timeLimit: Duration(seconds: 12)));
    return (latitude: position.latitude, longitude: position.longitude);
  }
}
