/// Short, deterministic copy explaining why a slot fits this visit.
String slotRationale({
  required DateTime slot,
  required String? option,
  required String vehicleMake,
  required String vehicleModel,
}) {
  final hour = slot.hour;
  final buffer = StringBuffer();
  if (hour < 11) {
    buffer.write('Morning slot — quieter floor, quicker handback.');
  } else if (hour < 15) {
    buffer.write(
        'Midday slot — good if you want a complimentary hour while you wait.');
  } else {
    buffer.write('Afternoon slot — plan for denser bay traffic.');
  }
  final vehicle = [
    vehicleMake.trim(),
    vehicleModel.trim(),
  ].where((part) => part.isNotEmpty).join(' ');
  if (vehicle.isNotEmpty) {
    buffer.write(' Tuned for your $vehicle.');
  }
  switch (option) {
    case 'BARBER':
      buffer.write(' Barber chair is held for the same hour.');
    case 'MASSAGE':
      buffer.write(' Massage room is held for the same hour.');
    case 'PRIVATE_WORK':
      buffer.write(' Private workspace is held for the same hour.');
    default:
      if (option == null || option.isEmpty) {
        buffer.write(' Express oil keeps the bay to the service hour.');
      }
  }
  return buffer.toString();
}
