import 'package:flutter_test/flutter_test.dart';

import '../lib/src/auth/access_token_provider.dart';

void main() {
  test('noop provider returns no token for local unauthenticated APIs',
      () async {
    expect(await NoopAccessTokenProvider().accessToken(), isNull);
  });

  test('callback provider delegates token loading', () async {
    final provider = CallbackAccessTokenProvider(() async => 'token-123');

    expect(await provider.accessToken(), 'token-123');
  });
}
