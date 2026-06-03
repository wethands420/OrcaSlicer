#include "ModelBrowserDialog.hpp"

#include "slic3r/GUI/DownloaderFileGet.hpp"
#include "slic3r/GUI/GUI.hpp"
#include "slic3r/GUI/GUI_App.hpp"
#include "slic3r/GUI/I18N.hpp"
#include "slic3r/GUI/MainFrame.hpp"
#include "slic3r/GUI/MsgDialog.hpp"
#include "slic3r/GUI/Plater.hpp"
#include "slic3r/GUI/Widgets/WebView.hpp"
#include "slic3r/GUI/format.hpp"

#include <boost/filesystem.hpp>
#include <boost/log/trivial.hpp>

#include <exception>
#include <vector>

#include <wx/button.h>
#include <wx/choice.h>
#include <wx/dirdlg.h>
#include <wx/filedlg.h>
#include <wx/sizer.h>
#include <wx/stattext.h>
#include <wx/stdpaths.h>
#include <wx/textctrl.h>
#include <wx/utils.h>

#ifdef __WIN32__
#include <WebView2.h>
#include <algorithm>
#include <wrl.h>
#endif

namespace Slic3r::GUI {
namespace {

const char* MODEL_BROWSER_DOWNLOAD_PATH_KEY = "model_browser_download_path";

struct ModelBrowserSite
{
    const char* label;
    const char* url;
};

const std::vector<ModelBrowserSite>& model_browser_sites()
{
    static const std::vector<ModelBrowserSite> sites = {
        {"Printables",  "https://www.printables.com/"},
        {"MakerWorld",  "https://makerworld.com/"},
        {"Thingiverse", "https://www.thingiverse.com/"},
        {"Cults3D",     "https://cults3d.com/"},
    };
    return sites;
}

bool is_http_url(const wxString& url)
{
    return url.StartsWith("https://") || url.StartsWith("http://");
}

bool is_makerworld_url(wxString url)
{
    url.MakeLower();
    return url.Contains("://makerworld.com") || url.Contains("://www.makerworld.com");
}

wxString normalized_url(wxString url)
{
    url.Trim(true);
    url.Trim(false);
    if (!url.Contains("://") && !url.empty())
        url = "https://" + url;
    return url;
}

boost::filesystem::path model_browser_default_download_dir()
{
    boost::filesystem::path path = boost::filesystem::temp_directory_path() / "OrcaSlicer-model-browser";
    boost::filesystem::create_directories(path);
    return path;
}

boost::filesystem::path unique_download_path(const boost::filesystem::path& folder, boost::filesystem::path filename)
{
    if (filename.empty())
        filename = "model-download";

    boost::filesystem::path candidate = folder / filename.filename();
    if (!boost::filesystem::exists(candidate))
        return candidate;

    const boost::filesystem::path stem = candidate.stem();
    const boost::filesystem::path ext = candidate.extension();
    for (int index = 1; index < 10000; ++index) {
        candidate = folder / (stem.string() + "-" + std::to_string(index) + ext.string());
        if (!boost::filesystem::exists(candidate))
            return candidate;
    }

    return folder / filename.filename();
}

std::string wx_to_utf8(const wxString& value)
{
    return std::string(value.ToUTF8().data());
}

ModelBrowser::FileType detect_download_type(const wxString& url, const wxString& suggested_filename)
{
    if (!suggested_filename.empty()) {
        const auto type = ModelBrowser::detect_file_type(wx_to_utf8(suggested_filename));
        if (type != ModelBrowser::FileType::Unknown)
            return type;
    }
    return ModelBrowser::detect_file_type(wx_to_utf8(url));
}

} // namespace

ModelBrowserDialog::ModelBrowserDialog(wxWindow* parent, Plater* plater)
    : DPIFrame(parent, wxID_ANY, _L("3D Model Browser"), wxDefaultPosition, wxSize(1200, 820),
               wxCLOSE_BOX | wxDEFAULT_FRAME_STYLE | wxRESIZE_BORDER)
    , m_plater(plater)
{
    SetMinSize(wxSize(800, 520));

    wxBoxSizer* main_sizer = new wxBoxSizer(wxVERTICAL);
    build_toolbar(main_sizer);

    m_browser = WebView::CreateWebView(this, wxEmptyString);
    if (m_browser == nullptr) {
        m_status->SetLabel(_L("Embedded browser is not available. Use the external browser button."));
    } else {
        WebView::SetUserAgent(m_browser,
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0");
        main_sizer->Add(m_browser, 1, wxEXPAND, 0);
        m_browser->Bind(wxEVT_WEBVIEW_NAVIGATING, &ModelBrowserDialog::on_navigation_request, this);
        m_browser->Bind(wxEVT_WEBVIEW_NAVIGATED, &ModelBrowserDialog::on_navigation_complete, this);
        m_browser->Bind(wxEVT_WEBVIEW_LOADED, &ModelBrowserDialog::on_document_loaded, this);
        m_browser->Bind(wxEVT_WEBVIEW_NEWWINDOW, &ModelBrowserDialog::on_new_window, this);
        m_browser->Bind(wxEVT_WEBVIEW_SCRIPT_MESSAGE_RECEIVED, &ModelBrowserDialog::on_script_message, this);
#ifdef __WIN32__
        install_native_download_interceptor();
#endif
    }

    SetSizer(main_sizer);
    Layout();
    Centre(wxBOTH);

    Bind(EVT_DWNLDR_FILE_PROGRESS, &ModelBrowserDialog::on_download_progress, this);
    Bind(EVT_DWNLDR_FILE_COMPLETE, &ModelBrowserDialog::on_download_complete, this);
    Bind(EVT_DWNLDR_FILE_ERROR, &ModelBrowserDialog::on_download_error, this);
    Bind(wxEVT_CLOSE_WINDOW, [this](wxCloseEvent& event) {
        Hide();
        event.Veto();
    });

    load_selected_site();
}

ModelBrowserDialog::~ModelBrowserDialog()
{
    if (m_active_download)
        m_active_download->cancel();
#ifdef __WIN32__
    remove_native_download_interceptor();
#endif
}

void ModelBrowserDialog::on_dpi_changed(const wxRect& /*suggested_rect*/)
{
    Layout();
}

void ModelBrowserDialog::build_toolbar(wxBoxSizer* parent_sizer)
{
    wxBoxSizer* toolbar = new wxBoxSizer(wxHORIZONTAL);

    auto* back = new wxButton(this, wxID_ANY, "<");
    auto* forward = new wxButton(this, wxID_ANY, ">");
    auto* reload = new wxButton(this, wxID_ANY, _L("Reload"));
    m_site_choice = new wxChoice(this, wxID_ANY);
    m_address = new wxTextCtrl(this, wxID_ANY, wxEmptyString, wxDefaultPosition, wxDefaultSize, wxTE_PROCESS_ENTER);
    auto* go = new wxButton(this, wxID_ANY, _L("Go"));
    auto* download = new wxButton(this, wxID_ANY, _L("Download"));
    auto* download_folder_button = new wxButton(this, wxID_ANY, _L("Download Folder"));
    auto* external = new wxButton(this, wxID_ANY, _L("Open in Browser"));

    for (const ModelBrowserSite& site : model_browser_sites())
        m_site_choice->Append(from_u8(site.label));
    m_site_choice->SetSelection(0);

    toolbar->Add(back, 0, wxALL, 4);
    toolbar->Add(forward, 0, wxALL, 4);
    toolbar->Add(reload, 0, wxALL, 4);
    toolbar->Add(m_site_choice, 0, wxALL | wxALIGN_CENTER_VERTICAL, 4);
    toolbar->Add(m_address, 1, wxALL | wxEXPAND, 4);
    toolbar->Add(go, 0, wxALL, 4);
    toolbar->Add(download, 0, wxALL, 4);
    toolbar->Add(download_folder_button, 0, wxALL, 4);
    toolbar->Add(external, 0, wxALL, 4);

    m_status = new wxStaticText(this, wxID_ANY,
        format_wxstr(_L("Downloads will be reviewed before import. Folder: %1%"), from_path(download_folder())));

    parent_sizer->Add(toolbar, 0, wxEXPAND, 0);
    parent_sizer->Add(m_status, 0, wxLEFT | wxRIGHT | wxBOTTOM | wxEXPAND, 6);

    m_site_choice->Bind(wxEVT_CHOICE, &ModelBrowserDialog::on_site_selected, this);
    m_address->Bind(wxEVT_TEXT_ENTER, &ModelBrowserDialog::on_address_enter, this);
    go->Bind(wxEVT_BUTTON, &ModelBrowserDialog::on_address_enter, this);
    back->Bind(wxEVT_BUTTON, &ModelBrowserDialog::on_back, this);
    forward->Bind(wxEVT_BUTTON, &ModelBrowserDialog::on_forward, this);
    reload->Bind(wxEVT_BUTTON, &ModelBrowserDialog::on_reload, this);
    external->Bind(wxEVT_BUTTON, &ModelBrowserDialog::on_open_external, this);
    download->Bind(wxEVT_BUTTON, &ModelBrowserDialog::on_download_current, this);
    download_folder_button->Bind(wxEVT_BUTTON, [this](wxCommandEvent&) { choose_download_folder(); });
}

void ModelBrowserDialog::load_selected_site()
{
    const int selection = m_site_choice ? m_site_choice->GetSelection() : wxNOT_FOUND;
    if (selection == wxNOT_FOUND)
        return;
    const auto& sites = model_browser_sites();
    if (static_cast<size_t>(selection) >= sites.size())
        return;
    load_url(from_u8(sites[selection].url));
}

void ModelBrowserDialog::load_url(const wxString& url)
{
    const wxString target = normalized_url(url);
    m_address->ChangeValue(target);
    if (m_browser)
        WebView::LoadUrl(m_browser, target);
}

void ModelBrowserDialog::open_external_browser()
{
    wxString url = m_browser ? m_browser->GetCurrentURL() : m_address->GetValue();
    if (url.empty())
        url = m_address->GetValue();
    if (!url.empty())
        wxLaunchDefaultBrowser(normalized_url(url));
}

boost::filesystem::path ModelBrowserDialog::download_folder() const
{
    std::string configured = wxGetApp().app_config ? wxGetApp().app_config->get(MODEL_BROWSER_DOWNLOAD_PATH_KEY) : std::string();
    if (!configured.empty()) {
        boost::filesystem::path path = into_path(from_u8(configured));
        boost::system::error_code ec;
        if (boost::filesystem::exists(path, ec) && boost::filesystem::is_directory(path, ec))
            return path;
    }

    return model_browser_default_download_dir();
}

void ModelBrowserDialog::choose_download_folder()
{
    const boost::filesystem::path current_folder = download_folder();
    wxDirDialog dialog(this, _L("Choose Model Browser Download Directory"), from_path(current_folder),
                       wxDD_DEFAULT_STYLE | wxDD_DIR_MUST_EXIST);
    if (dialog.ShowModal() != wxID_OK)
        return;

    const boost::filesystem::path selected = into_path(dialog.GetPath());
    if (wxGetApp().app_config)
        wxGetApp().app_config->set(MODEL_BROWSER_DOWNLOAD_PATH_KEY, into_u8(dialog.GetPath()));

    m_status->SetLabel(format_wxstr(_L("Downloads will be reviewed before import. Folder: %1%"), from_path(selected)));
}

void ModelBrowserDialog::on_site_selected(wxCommandEvent& /*event*/)
{
    load_selected_site();
}

void ModelBrowserDialog::on_address_enter(wxCommandEvent& /*event*/)
{
    load_url(m_address->GetValue());
}

void ModelBrowserDialog::on_back(wxCommandEvent& /*event*/)
{
    if (m_browser && m_browser->CanGoBack())
        m_browser->GoBack();
}

void ModelBrowserDialog::on_forward(wxCommandEvent& /*event*/)
{
    if (m_browser && m_browser->CanGoForward())
        m_browser->GoForward();
}

void ModelBrowserDialog::on_reload(wxCommandEvent& /*event*/)
{
    if (m_browser)
        m_browser->Reload();
}

void ModelBrowserDialog::on_open_external(wxCommandEvent& /*event*/)
{
    open_external_browser();
}

void ModelBrowserDialog::on_download_current(wxCommandEvent& /*event*/)
{
    const wxString url = normalized_url(m_address->GetValue());
    if (!is_http_url(url) || !maybe_handle_download_request(url)) {
        MessageDialog dlg(this,
            _L("The current address does not look like a direct STL, OBJ, STEP, 3MF, ZIP, AMF, SVG, or DRC download link."),
            _L("Download"), wxOK | wxICON_INFORMATION);
        dlg.ShowModal();
    }
}

void ModelBrowserDialog::on_navigation_request(wxWebViewEvent& event)
{
    const wxString url = event.GetURL();
    m_address->ChangeValue(url);

    if (is_http_url(url) && maybe_handle_download_request(url)) {
        event.Veto();
        return;
    }

    event.Skip();
}

void ModelBrowserDialog::on_navigation_complete(wxWebViewEvent& event)
{
    m_address->ChangeValue(event.GetURL());
    event.Skip();
}

void ModelBrowserDialog::on_new_window(wxWebViewEvent& event)
{
    const wxString url = event.GetURL();
    event.Veto();
    if (is_http_url(url) && maybe_handle_download_request(url)) {
        return;
    }
    load_url(url);
}

void ModelBrowserDialog::on_document_loaded(wxWebViewEvent& event)
{
#ifdef __WIN32__
    if (!m_native_download_interceptor_installed)
        install_native_download_interceptor();
#endif
    inject_page_bridge();
    event.Skip();
}

void ModelBrowserDialog::on_script_message(wxWebViewEvent& event)
{
    const wxString message = event.GetString();
    static const wxString navigate_prefix = "model-browser:navigate:";
    static const wxString download_prefix = "model-browser:download:";
    static const wxString location_prefix = "model-browser:location:";

    if (message.StartsWith(download_prefix)) {
        maybe_handle_download_request(message.Mid(download_prefix.length()));
        return;
    }
    if (message.StartsWith(navigate_prefix)) {
        load_url(message.Mid(navigate_prefix.length()));
        return;
    }
    if (message.StartsWith(location_prefix)) {
        m_address->ChangeValue(message.Mid(location_prefix.length()));
        return;
    }

    event.Skip();
}

bool ModelBrowserDialog::maybe_handle_download_request(const wxString& url, const wxString& suggested_filename)
{
    if (!is_http_url(url))
        return false;

    const auto file_type = detect_download_type(url, suggested_filename);
    if (file_type == ModelBrowser::FileType::Unknown)
        return false;

    start_download(url, suggested_filename);
    return true;
}

void ModelBrowserDialog::inject_page_bridge()
{
    if (!m_browser)
        return;

    const wxString current_url = m_browser->GetCurrentURL().empty() ? m_address->GetValue() : m_browser->GetCurrentURL();
    if (is_makerworld_url(current_url))
        return;

    const wxString script = R"JS(
        (function() {
            if (window.__orcaModelBrowserBridgeInstalled)
                return;
            window.__orcaModelBrowserBridgeInstalled = true;

            const exts = ['.stl', '.obj', '.stp', '.step', '.3mf', '.zip', '.amf', '.svg', '.drc'];
            function toAbsoluteUrl(url) {
                try {
                    return new URL(String(url), window.location.href).href;
                } catch (e) {
                    return String(url || '');
                }
            }
            function isModelDownloadUrl(url) {
                try {
                    const value = new URL(url, window.location.href);
                    const path = (value.pathname || '').toLowerCase();
                    return exts.some(ext => path.endsWith(ext));
                } catch (e) {
                    return false;
                }
            }

            function post(message) {
                if (window.wx && typeof window.wx.postMessage === 'function')
                    window.wx.postMessage(message);
            }
            function postLocation() {
                post('model-browser:location:' + window.location.href);
            }

            document.addEventListener('click', function(event) {
                const anchor = event.target && event.target.closest ? event.target.closest('a[href]') : null;
                if (!anchor || !anchor.href)
                    return;
                if (isModelDownloadUrl(anchor.href)) {
                    event.preventDefault();
                    event.stopPropagation();
                    post('model-browser:download:' + toAbsoluteUrl(anchor.href));
                    return;
                }
                if (anchor.hasAttribute('download')) {
                    return;
                }
            }, true);

            const originalOpen = window.open;
            function createPopupProxy() {
                const proxyLocation = {
                    assign(value) {
                        if (value)
                            post('model-browser:navigate:' + toAbsoluteUrl(value));
                    },
                    replace(value) {
                        if (value)
                            post('model-browser:navigate:' + toAbsoluteUrl(value));
                    },
                    set href(value) {
                        if (value)
                            post('model-browser:navigate:' + toAbsoluteUrl(value));
                    },
                    get href() {
                        return window.location.href;
                    }
                };

                return {
                    closed: false,
                    close() { this.closed = true; },
                    focus() {},
                    blur() {},
                    postMessage() {},
                    location: proxyLocation
                };
            }
            window.open = function(url) {
                const popup = createPopupProxy();
                if (url) {
                    post('model-browser:navigate:' + toAbsoluteUrl(url));
                    return popup;
                }
                return popup || originalOpen.apply(this, arguments);
            };

            const originalPushState = history.pushState;
            history.pushState = function() {
                const result = originalPushState.apply(this, arguments);
                postLocation();
                return result;
            };

            const originalReplaceState = history.replaceState;
            history.replaceState = function() {
                const result = originalReplaceState.apply(this, arguments);
                postLocation();
                return result;
            };

            window.addEventListener('popstate', postLocation);
            window.addEventListener('hashchange', postLocation);
            postLocation();
        })();
    )JS";
    WebView::RunScript(m_browser, script);
}

void ModelBrowserDialog::start_download(const wxString& url, const wxString& suggested_filename)
{
    if (m_active_download) {
        MessageDialog dlg(this, _L("A model download is already running."), _L("Download"), wxOK | wxICON_INFORMATION);
        dlg.ShowModal();
        return;
    }

    const std::string url_utf8 = wx_to_utf8(url);
    const auto file_type = detect_download_type(url, suggested_filename);
    if (file_type == ModelBrowser::FileType::Unknown) {
        MessageDialog dlg(this, _L("This link does not point to a supported model file."), _L("Download"), wxOK | wxICON_WARNING);
        dlg.ShowModal();
        return;
    }

    std::string filename = !suggested_filename.empty() ? wx_to_utf8(suggested_filename) : std::string();
    if (filename.empty())
        filename = ModelBrowser::filename_from_url(url_utf8, "model-download");
    try {
        const boost::filesystem::path dest = download_folder();
        m_active_download = std::make_shared<FileGet>(m_next_download_id++, url_utf8, filename, this, dest);
        m_status->SetLabel(_L("Downloading model file..."));
        m_active_download->get();
    } catch (const std::exception& e) {
        m_active_download.reset();
        MessageDialog dlg(this, from_u8(e.what()), _L("Download"), wxOK | wxICON_WARNING);
        dlg.ShowModal();
    }
}

void ModelBrowserDialog::on_download_progress(wxCommandEvent& event)
{
    m_status->SetLabel(format_wxstr(_L("Downloading model file... %1%"), event.GetString() + "%"));
}

void ModelBrowserDialog::on_download_complete(wxCommandEvent& event)
{
    const boost::filesystem::path path = into_path(event.GetString());
    m_active_download.reset();
    m_status->SetLabel(format_wxstr(_L("Downloaded: %1%"), event.GetString()));
    handle_downloaded_file(path);
}

void ModelBrowserDialog::on_download_error(wxCommandEvent& event)
{
    m_active_download.reset();
    m_status->SetLabel(_L("Download failed."));
    MessageDialog dlg(this, event.GetString(), _L("Download failed"), wxOK | wxICON_WARNING);
    dlg.ShowModal();
}

void ModelBrowserDialog::handle_downloaded_file(const boost::filesystem::path& path)
{
    const auto file_type = ModelBrowser::detect_file_type(path.string());
    switch (ModelBrowser::default_import_action(file_type)) {
    case ModelBrowser::ImportAction::ImportGeometry: {
        MessageDialog dlg(this,
            _L("Import the downloaded file as geometry?\n\nThis will add model geometry to the current project."),
            _L("Import downloaded model"), wxYES | wxNO | wxCANCEL | wxICON_QUESTION);
        dlg.SetButtonLabel(wxID_YES, _L("Import Geometry"));
        dlg.SetButtonLabel(wxID_NO, _L("Save As"));
        const int result = dlg.ShowModal();
        if (result == wxID_YES)
            import_geometry(path);
        else if (result == wxID_NO)
            save_file_as(path);
        break;
    }
    case ModelBrowser::ImportAction::AskProjectOrGeometry: {
        MessageDialog dlg(this,
            _L("The downloaded 3MF may contain project, printer, filament, or process settings.\n\nChoose how OrcaSlicer should handle it."),
            _L("3MF download"), wxYES | wxNO | wxCANCEL | wxICON_QUESTION);
        dlg.SetButtonLabel(wxID_YES, _L("Import Geometry Only"));
        dlg.SetButtonLabel(wxID_NO, _L("Open as Project"));
        const int result = dlg.ShowModal();
        if (result == wxID_YES)
            import_geometry(path);
        else if (result == wxID_NO)
            open_project(path);
        break;
    }
    case ModelBrowser::ImportAction::InspectArchive: {
        MessageDialog dlg(this,
            _L("The downloaded ZIP archive will be inspected and OrcaSlicer will show supported model files before importing."),
            _L("ZIP download"), wxYES | wxNO | wxCANCEL | wxICON_QUESTION);
        dlg.SetButtonLabel(wxID_YES, _L("Inspect Archive"));
        dlg.SetButtonLabel(wxID_NO, _L("Save As"));
        const int result = dlg.ShowModal();
        if (result == wxID_YES)
            inspect_zip_archive(path);
        else if (result == wxID_NO)
            save_file_as(path);
        break;
    }
    case ModelBrowser::ImportAction::SaveOnly:
        save_file_as(path);
        break;
    }
}

void ModelBrowserDialog::import_geometry(const boost::filesystem::path& path)
{
    if (!m_plater)
        return;
    wxGetApp().mainframe->select_tab(MainFrame::tp3DEditor);
    m_plater->load_files(std::vector<boost::filesystem::path>{path}, LoadStrategy::LoadModel, false);
}

void ModelBrowserDialog::open_project(const boost::filesystem::path& path)
{
    if (!m_plater)
        return;
    m_plater->load_project(from_path(path), "<loadall>");
}

void ModelBrowserDialog::inspect_zip_archive(const boost::filesystem::path& path)
{
    if (!m_plater)
        return;
    wxGetApp().mainframe->select_tab(MainFrame::tp3DEditor);
    m_plater->preview_zip_archive(path);
}

void ModelBrowserDialog::save_file_as(const boost::filesystem::path& path)
{
    wxFileDialog dlg(this, _L("Save downloaded file"), wxEmptyString, from_path(path.filename()), "*.*",
                     wxFD_SAVE | wxFD_OVERWRITE_PROMPT);
    if (dlg.ShowModal() != wxID_OK)
        return;

    try {
        boost::filesystem::copy_file(path, into_path(dlg.GetPath()), boost::filesystem::copy_options::overwrite_existing);
    } catch (const boost::filesystem::filesystem_error& e) {
        MessageDialog error_dlg(this, from_u8(e.what()), _L("Save downloaded file"), wxOK | wxICON_WARNING);
        error_dlg.ShowModal();
    }
}

#ifdef __WIN32__
struct ModelBrowserDialog::NativeDownload
{
    ICoreWebView2DownloadOperation* operation{nullptr};
    EventRegistrationToken state_changed_token{};
    boost::filesystem::path target_path;
    bool handled{false};
    bool state_handler_registered{false};

    ~NativeDownload()
    {
        if (operation) {
            if (state_handler_registered)
                operation->remove_StateChanged(state_changed_token);
            operation->Release();
        }
    }
};

void ModelBrowserDialog::install_native_download_interceptor()
{
    if (!m_browser || m_native_download_interceptor_installed)
        return;

    auto* backend = reinterpret_cast<ICoreWebView2*>(m_browser->GetNativeBackend());
    if (backend == nullptr)
        return;

    ICoreWebView2_4* webview4 = nullptr;
    if (FAILED(backend->QueryInterface(&webview4)) || webview4 == nullptr)
        return;

    EventRegistrationToken token{};
    const HRESULT hr = webview4->add_DownloadStarting(
        Microsoft::WRL::Callback<ICoreWebView2DownloadStartingEventHandler>(
            [this](ICoreWebView2* /*sender*/, ICoreWebView2DownloadStartingEventArgs* args) -> HRESULT {
                LPWSTR result_file_path = nullptr;
                if (FAILED(args->get_ResultFilePath(&result_file_path)))
                    return S_OK;

                const wxString suggested = result_file_path ? wxString(result_file_path) : wxEmptyString;
                CoTaskMemFree(result_file_path);

                ICoreWebView2DownloadOperation* operation = nullptr;
                if (FAILED(args->get_DownloadOperation(&operation)) || operation == nullptr)
                    return S_OK;

                LPWSTR uri = nullptr;
                const HRESULT uri_hr = operation->get_Uri(&uri);
                if (FAILED(uri_hr)) {
                    operation->Release();
                    return S_OK;
                }

                const wxString url = uri ? wxString(uri) : wxEmptyString;
                CoTaskMemFree(uri);

                const auto file_type = detect_download_type(url, suggested);
                if (file_type == ModelBrowser::FileType::Unknown) {
                    operation->Release();
                    return S_OK;
                }

                const boost::filesystem::path target_path = unique_download_path(
                    download_folder(),
                    boost::filesystem::path(wx_to_utf8(!suggested.empty() ? suggested : wxString("model-download"))).filename());

                const std::wstring target_wide = target_path.wstring();
                if (FAILED(args->put_ResultFilePath(target_wide.c_str()))) {
                    operation->Release();
                    return S_OK;
                }

                auto download = std::make_shared<NativeDownload>();
                download->operation = operation;
                download->target_path = target_path;

                args->put_Handled(TRUE);
                args->put_Cancel(FALSE);

                EventRegistrationToken state_token{};
                const HRESULT state_hr = operation->add_StateChanged(
                    Microsoft::WRL::Callback<ICoreWebView2StateChangedEventHandler>(
                        [this, download](ICoreWebView2DownloadOperation* sender, IUnknown* /*args*/) -> HRESULT {
                            COREWEBVIEW2_DOWNLOAD_STATE state = COREWEBVIEW2_DOWNLOAD_STATE_IN_PROGRESS;
                            if (FAILED(sender->get_State(&state)))
                                return S_OK;

                            if (state == COREWEBVIEW2_DOWNLOAD_STATE_COMPLETED && !download->handled) {
                                download->handled = true;
                                CallAfter([this, path = download->target_path]() {
                                    m_status->SetLabel(format_wxstr(_L("Downloaded: %1%"), from_path(path)));
                                    handle_downloaded_file(path);
                                });
                            } else if (state == COREWEBVIEW2_DOWNLOAD_STATE_INTERRUPTED && !download->handled) {
                                download->handled = true;
                                CallAfter([this]() {
                                    m_status->SetLabel(_L("Download failed."));
                                    MessageDialog dlg(this, _L("The browser download was interrupted."), _L("Download failed"),
                                                      wxOK | wxICON_WARNING);
                                    dlg.ShowModal();
                                });
                            }

                            if (state != COREWEBVIEW2_DOWNLOAD_STATE_IN_PROGRESS) {
                                CallAfter([this, download]() {
                                    auto it = std::find(m_native_downloads.begin(), m_native_downloads.end(), download);
                                    if (it != m_native_downloads.end())
                                        m_native_downloads.erase(it);
                                });
                            }
                            return S_OK;
                        }).Get(),
                    &state_token);

                if (FAILED(state_hr))
                    return S_OK;

                download->state_changed_token = state_token;
                download->state_handler_registered = true;
                m_native_downloads.push_back(download);
                m_status->SetLabel(_L("Downloading model file..."));
                return S_OK;
            }).Get(),
        &token);

    webview4->Release();
    if (FAILED(hr))
        return;

    m_native_download_interceptor_installed = true;
    m_native_download_interceptor_token_value = token.value;
}

void ModelBrowserDialog::remove_native_download_interceptor()
{
    if (!m_browser || !m_native_download_interceptor_installed)
        return;

    auto* backend = reinterpret_cast<ICoreWebView2*>(m_browser->GetNativeBackend());
    if (backend == nullptr)
        return;

    ICoreWebView2_4* webview4 = nullptr;
    if (FAILED(backend->QueryInterface(&webview4)) || webview4 == nullptr)
        return;

    EventRegistrationToken token{};
    token.value = m_native_download_interceptor_token_value;
    webview4->remove_DownloadStarting(token);
    webview4->Release();

    m_native_downloads.clear();
    m_native_download_interceptor_installed = false;
    m_native_download_interceptor_token_value = 0;
}
#endif

} // namespace Slic3r::GUI
