import 'package:flutter_test/flutter_test.dart';

import '../lib/src/payment/payment_checkout.dart';
import '../lib/src/payment/payment_repository.dart';

class FakePaymentRepository extends PaymentRepository {
  FakePaymentRepository(this.next, {this.refreshed}) : super();

  final PaymentOrder next;
  final PaymentOrder? refreshed;
  String? requestedId;

  @override
  Future<PaymentOrder> startCheckout(String paymentId,
      {String? idempotencyKey}) async {
    requestedId = paymentId;
    return next;
  }

  @override
  Future<PaymentOrder?> findOrder(String id) async => refreshed;
}

class FakeLauncher implements HostedCheckoutLauncher {
  bool opened = false;
  Uri? url;

  @override
  Future<bool> open(Uri checkoutUrl) async {
    opened = true;
    url = checkoutUrl;
    return true;
  }
}

class FakePendingStore implements PendingCheckoutStore {
  String? value;

  @override
  Future<String?> read() async => value;

  @override
  Future<void> write(String paymentId) async => value = paymentId;

  @override
  Future<void> clear() async => value = null;
}

PaymentOrder order({String status = 'PENDING', String? url}) => PaymentOrder(
      id: 'payment-1',
      customerId: 'customer-1',
      holdId: 'hold-1',
      amountMinor: 24900,
      currency: 'ZAR',
      provider: 'YOCO',
      status: status,
      providerReference: 'ch-1',
      checkoutUrl: url,
      createdAt: DateTime.utc(2026, 10, 12),
    );

void main() {
  test('opens the hosted checkout and persists the pending payment', () async {
    final launcher = FakeLauncher();
    final pending = FakePendingStore();
    final coordinator = PaymentCheckoutCoordinator(
      payments: FakePaymentRepository(
        order(url: 'https://checkout.yoco.test/ch-1'),
      ),
      launcher: launcher,
      pendingStore: pending,
    );

    final started = await coordinator.begin('payment-1');

    expect(started.checkoutUrl, 'https://checkout.yoco.test/ch-1');
    expect(launcher.opened, isTrue);
    expect(launcher.url.toString(), 'https://checkout.yoco.test/ch-1');
    expect(pending.value, 'payment-1');
  });

  test('refreshes status from a valid deep-link return and clears paid state',
      () async {
    final pending = FakePendingStore()..value = 'payment-1';
    final coordinator = PaymentCheckoutCoordinator(
      payments: FakePaymentRepository(order(status: 'PENDING'),
          refreshed: order(status: 'PAID')),
      launcher: FakeLauncher(),
      pendingStore: pending,
    );

    final returned = await coordinator
        .handleReturn(Uri.parse('serv60://payment-return?status=success'));

    expect(returned?.status, 'PAID');
    expect(coordinator.order?.status, 'PAID');
    expect(pending.value, isNull);
  });

  test('ignores unrelated deep links and preserves pending state', () async {
    final pending = FakePendingStore()..value = 'payment-1';
    final coordinator = PaymentCheckoutCoordinator(
      payments: FakePaymentRepository(order(status: 'PENDING')),
      launcher: FakeLauncher(),
      pendingStore: pending,
    );

    expect(await coordinator.handleReturn(Uri.parse('https://example.test')),
        isNull);
    expect(pending.value, 'payment-1');
  });

  test('rejects a non-web checkout URL before opening it', () async {
    final launcher = FakeLauncher();
    final pending = FakePendingStore();
    final coordinator = PaymentCheckoutCoordinator(
      payments: FakePaymentRepository(order(url: 'javascript:alert(1)')),
      launcher: launcher,
      pendingStore: pending,
    );

    expect(() => coordinator.begin('payment-1'), throwsStateError);
    expect(launcher.opened, isFalse);
    expect(pending.value, isNull);
  });
}
