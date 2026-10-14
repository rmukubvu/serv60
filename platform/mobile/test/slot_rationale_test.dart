import 'package:flutter_test/flutter_test.dart';
import 'package:serv60_mobile/src/journey/slot_rationale.dart';

void main() {
  test('slot rationale mentions vehicle and complimentary option', () {
    final copy = slotRationale(
      slot: DateTime(2035, 3, 10, 10, 30),
      option: 'BARBER',
      vehicleMake: 'Toyota',
      vehicleModel: 'Corolla',
    );
    expect(copy, contains('Morning slot'));
    expect(copy, contains('Toyota Corolla'));
    expect(copy, contains('Barber chair'));
  });

  test('afternoon express oil keeps bay language', () {
    final copy = slotRationale(
      slot: DateTime(2035, 3, 10, 16, 0),
      option: null,
      vehicleMake: '',
      vehicleModel: '',
    );
    expect(copy, contains('Afternoon slot'));
    expect(copy, contains('Express oil'));
  });
}
