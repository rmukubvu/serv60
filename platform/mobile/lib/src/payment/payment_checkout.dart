import 'dart:async';

import 'package:app_links/app_links.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:url_launcher/url_launcher.dart';

import 'payment_repository.dart';

abstract interface class PendingCheckoutStore {
  Future<String?> read();

  Future<void> write(String paymentId);

  Future<void> clear();
}

class SecurePendingCheckoutStore implements PendingCheckoutStore {
  SecurePendingCheckoutStore({FlutterSecureStorage? storage})
      : storage = storage ?? const FlutterSecureStorage();

  static const key = 'serv60.payment.pending-order';
  final FlutterSecureStorage storage;

  @override
  Future<String?> read() => storage.read(key: key);

  @override
  Future<void> write(String paymentId) =>
      storage.write(key: key, value: paymentId);

  @override
  Future<void> clear() => storage.delete(key: key);
}

abstract interface class HostedCheckoutLauncher {
  Future<bool> open(Uri checkoutUrl);
}

class UrlLauncherHostedCheckout implements HostedCheckoutLauncher {
  @override
  Future<bool> open(Uri checkoutUrl) =>
      launchUrl(checkoutUrl, mode: LaunchMode.externalApplication);
}

class PaymentCheckoutCoordinator extends ChangeNotifier {
  PaymentCheckoutCoordinator({
    required this.payments,
    HostedCheckoutLauncher? launcher,
    PendingCheckoutStore? pendingStore,
    this.returnScheme = const String.fromEnvironment(
      'SERV60_PAYMENT_RETURN_SCHEME',
      defaultValue: 'serv60',
    ),
    this.returnHost = const String.fromEnvironment(
      'SERV60_PAYMENT_RETURN_HOST',
      defaultValue: 'payment-return',
    ),
  })  : launcher = launcher ?? UrlLauncherHostedCheckout(),
        pendingStore = pendingStore ?? SecurePendingCheckoutStore();

  final PaymentRepository payments;
  final HostedCheckoutLauncher launcher;
  final PendingCheckoutStore pendingStore;
  final String returnScheme;
  final String returnHost;
  PaymentOrder? order;
  StreamSubscription<Uri>? _deepLinkSubscription;

  Future<PaymentOrder> begin(String paymentId) async {
    final checkout = await payments.startCheckout(paymentId);
    final checkoutUrl = checkout.checkoutUrl;
    if (checkoutUrl == null || checkoutUrl.isEmpty) {
      throw StateError('The payment provider did not return a checkout URL');
    }
    final checkoutUri = Uri.tryParse(checkoutUrl);
    if (checkoutUri == null ||
        !const {'http', 'https'}.contains(checkoutUri.scheme) ||
        checkoutUri.host.isEmpty) {
      throw StateError('The payment provider returned an invalid checkout URL');
    }
    await pendingStore.write(paymentId);
    final opened = await launcher.open(checkoutUri);
    if (!opened) throw StateError('The payment checkout could not be opened');
    order = checkout;
    notifyListeners();
    return checkout;
  }

  Future<PaymentOrder?> handleReturn(Uri returnUri) async {
    if (returnUri.scheme != returnScheme || returnUri.host != returnHost) {
      return null;
    }
    final paymentId = returnUri.queryParameters['paymentId'] ??
        returnUri.queryParameters['paymentOrderId'] ??
        await pendingStore.read();
    if (paymentId == null || paymentId.isEmpty) return null;
    final refreshed = await payments.findOrder(paymentId);
    if (refreshed == null) return null;
    order = refreshed;
    notifyListeners();
    if (_isTerminal(refreshed.status)) await pendingStore.clear();
    return refreshed;
  }

  Future<void> listenForReturns({AppLinks? appLinks}) async {
    await _deepLinkSubscription?.cancel();
    final links = appLinks ?? AppLinks();
    _deepLinkSubscription = links.uriLinkStream.listen((uri) {
      unawaited(handleReturn(uri));
    });
    final initial = await links.getInitialLink();
    if (initial != null) await handleReturn(initial);
  }

  @override
  void dispose() {
    unawaited(_deepLinkSubscription?.cancel());
    super.dispose();
  }

  // Refunds rank above PAID on the server, so they end polling too.
  bool _isTerminal(String status) => const {
        'PAID',
        'FAILED',
        'CANCELLED',
        'PARTIALLY_REFUNDED',
        'REFUNDED'
      }.contains(status);
}
