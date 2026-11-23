// FrontPOS Support: tilknytning til FrontPOS365's backoffice med en supportkode (23. nov 2026).
// Selve kaldet og heartbeat ligger i Kotlin (FrontPosSupport.kt), så det også virker efter genstart uden denne skærm.
import 'package:flutter/material.dart';

import '../../common.dart';
import 'server_page.dart';

class FrontPosLinkCard extends StatefulWidget {
  const FrontPosLinkCard({Key? key}) : super(key: key);

  @override
  State<FrontPosLinkCard> createState() => _FrontPosLinkCardState();
}

class _FrontPosLinkCardState extends State<FrontPosLinkCard> {
  final _code = TextEditingController();
  Map<dynamic, dynamic>? _status;
  bool _busy = false;
  String? _message;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final status =
        await gFFI.invokeMethodWithResult<Map<dynamic, dynamic>>("frontpos_status");
    if (mounted) setState(() => _status = status);
  }

  Future<void> _register() async {
    final code = _code.text.replaceAll(RegExp(r'[\s-]'), '').toUpperCase();
    if (code.isEmpty) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    final result = await gFFI
        .invokeMethodWithResult<Map<dynamic, dynamic>>("frontpos_register", code);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _failed = result?["ok"] != true;
      _message = result?["message"]?.toString() ?? "Tilknytningen mislykkedes.";
      if (!_failed) _code.clear();
    });
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    if (status == null) return const SizedBox.shrink();
    final registered = status["registered"] == true;
    final tenant = status["tenantName"]?.toString() ?? "";
    final name = status["name"]?.toString() ?? "";

    return PaddingCard(
      title: "FrontPOS",
      titleIcon: const Icon(Icons.support_agent, color: MyTheme.accent),
      child: registered
          ? Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(tenant.isEmpty ? "Tilknyttet FrontPOS." : "Tilknyttet $tenant",
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              if (name.isNotEmpty) Text(name),
              const SizedBox(height: 4),
              const Text(
                  "FrontPOS og din forhandler kan hjælpe på denne enhed. Du kan altid se, når de er på, og afbryde."),
              if (_message != null) ...[
                const SizedBox(height: 6),
                Text(_message!, style: TextStyle(color: _failed ? Colors.red : Colors.green)),
              ],
            ])
          : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text(
                  "Tast supportkoden fra FrontPOS eller din forhandler. Det skal kun gøres én gang."),
              const SizedBox(height: 8),
              Row(children: [
                Expanded(
                  child: TextField(
                    controller: _code,
                    textCapitalization: TextCapitalization.characters,
                    decoration: const InputDecoration(
                        labelText: "Supportkode", border: OutlineInputBorder(), isDense: true),
                    onSubmitted: (_) => _register(),
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: _busy ? null : _register,
                  child: _busy
                      ? const SizedBox(
                          width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text("Tilknyt"),
                ),
              ]),
              if (_message != null) ...[
                const SizedBox(height: 6),
                Text(_message!, style: TextStyle(color: _failed ? Colors.red : Colors.green)),
              ],
            ]),
    );
  }
}
