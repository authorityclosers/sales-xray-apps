// Sales Xray for Mac and Windows: the Sales Xray site in its own window.
// The window and the site's address come from tauri.conf.json. The site gets
// no access to the computer: no capability grants IPC to remote pages.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("Sales Xray could not start");
}
