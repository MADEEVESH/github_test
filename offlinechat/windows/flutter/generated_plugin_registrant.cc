//
//  Generated file. Do not edit.
//

// clang-format off

#include "generated_plugin_registrant.h"

#include <flutter_ble_central/flutter_ble_central_plugin_c_api.h>
#include <flutter_ble_peripheral/flutter_ble_peripheral_plugin_c_api.h>

void RegisterPlugins(flutter::PluginRegistry* registry) {
  FlutterBleCentralPluginCApiRegisterWithRegistrar(
      registry->GetRegistrarForPlugin("FlutterBleCentralPluginCApi"));
  FlutterBlePeripheralPluginCApiRegisterWithRegistrar(
      registry->GetRegistrarForPlugin("FlutterBlePeripheralPluginCApi"));
}
