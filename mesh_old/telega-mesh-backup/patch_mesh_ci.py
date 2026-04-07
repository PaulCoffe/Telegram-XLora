import os
import sys

def patch_messages_controller():
    print("Patching MessagesController.java...")
    mc_path = "app/src/main/java/org/thunderdog/challegram/ui/MessagesController.java"
    if not os.path.exists(mc_path):
        print(f"Error: {mc_path} not found")
        return

    with open(mc_path, "r") as f:
        lines = f.readlines()

    # 1. Add Imports
    lines.insert(20, "import org.thunderdog.challegram.mesh.*;\n")
    lines.insert(21, "import android.widget.ImageView;\n")
    lines.insert(22, "import android.util.Log;\n")

    new_lines = []
    field_injected = False
    button_injected = False
    layout_injected = False
    routing_injected = False

    for line in lines:
        new_lines.append(line)
        
        # Add field declaration
        if not field_injected and "private final Tdlib tdlib;" in line:
            new_lines.append("  private ImageView meshButton;\n")
            field_injected = True
        
        # Inject Button Setup
        if not button_injected and "recordButton = new VoiceVideoButtonView(context);" in line:
            btn_logic = [
                "    meshButton = new InvisibleImageView(context);\n",
                "    meshButton.setId(R.id.btn_mesh);\n",
                "    meshButton.setColorFilter(Theme.iconColor());\n",
                "    addThemeFilterListener(meshButton, ColorId.icon);\n",
                "    meshButton.setScaleType(ImageView.ScaleType.CENTER);\n",
                "    meshButton.setOnClickListener(v -> {\n",
                "        boolean meshEnabled = !MeshTransportManager.getInstance().isMeshMode();\n",
                "        MeshTransportManager.getInstance().setMeshMode(meshEnabled);\n",
                "        updateMeshButton(meshEnabled);\n",
                "    });\n",
                "    meshButton.setVisibility(View.VISIBLE);\n",
                "    meshButton.setLayoutParams(lp);\n",
                "    meshButton.setImageResource(R.drawable.baseline_gps_fixed_24);\n"
            ]
            new_lines.extend(btn_logic)
            button_injected = True

        # Inject to Layout
        if not layout_injected and "attachButtons.addView(commandButton);" in line:
            new_lines.append("    attachButtons.addView(meshButton);\n")
            layout_injected = True

        # Routing Logic
        if not routing_injected and "tdlib.sendMessage(chat.id, topicId, replyTo, sendOptions, content, after);" in line:
            routing = [
                "        if (MeshTransportManager.getInstance().shouldRouteViaMesh(tdlib.getNetworkType())) {\n",
                "            if (content instanceof TdApi.InputMessageText) {\n",
                "                TdApi.InputMessageText textContent = (TdApi.InputMessageText) content;\n",
                "                byte[] rawMessage = textContent.text.text.getBytes();\n",
                "                MeshManager meshManager = new MeshManager(context(), tdlib);\n",
                "                MeshFragmenter fragmenter = new MeshFragmenter();\n",
                "                List<MeshFragmenter.Fragment> fragments = fragmenter.fragment(rawMessage, (int) System.currentTimeMillis());\n",
                "                for (MeshFragmenter.Fragment f : fragments) { meshManager.sendData(f.serialize()); }\n",
                "                Log.i(\"TelegaMesh\", \"Message sent via LoRa Mesh\");\n",
                "            }\n",
                "        }\n"
            ]
            # Insert before the sendMessage call
            new_lines.insert(-1, "".join(routing))
            routing_injected = True

    # Add updateMeshButton method before the final '}'
    update_method = """
  private void updateMeshButton(boolean enabled) {
    if (meshButton != null) {
        int color = enabled ? 0xFF00E5FF : Theme.iconColor();
        meshButton.setColorFilter(color);
        U.showToast(enabled ? "Mesh Mode: ON" : "Mesh Mode: OFF");
    }
  }
"""
    new_lines.insert(-1, update_method)

    with open(mc_path, "w") as f:
        f.writelines(new_lines)
    print("MessagesController.java patched successfully.")

def patch_ids():
    print("Patching ids.xml...")
    ids_path = "app/src/main/res/values/ids.xml"
    if not os.path.exists(ids_path):
        print(f"Error: {ids_path} not found")
        return

    with open(ids_path, "r") as f:
        ids_lines = f.readlines()
    
    # Inject IDs before the last </resources>
    mesh_ids = '  <item type="id" name="btn_mesh" />\n  <item type="id" name="btn_mesh_status" />\n'
    ids_lines.insert(-1, mesh_ids)
    
    with open(ids_path, "w") as f:
        f.writelines(ids_lines)
    print("ids.xml patched successfully.")

if __name__ == "__main__":
    patch_messages_controller()
    patch_ids()
