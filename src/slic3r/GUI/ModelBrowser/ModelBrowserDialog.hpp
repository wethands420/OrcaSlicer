#ifndef slic3r_GUI_ModelBrowserDialog_hpp_
#define slic3r_GUI_ModelBrowserDialog_hpp_

#include "ModelBrowserFile.hpp"
#include "slic3r/GUI/GUI_Utils.hpp"

#include <boost/filesystem/path.hpp>
#include <memory>
#include <vector>

#include <wx/panel.h>
#include <wx/webview.h>

class wxBoxSizer;
class wxButton;
class wxChoice;
class wxCommandEvent;
class wxStaticText;
class wxTextCtrl;
class wxWebViewEvent;

namespace Slic3r::GUI {

class FileGet;
class Plater;

class ModelBrowserPanel : public wxPanel
{
public:
    ModelBrowserPanel(wxWindow* parent, Plater* plater);
    ~ModelBrowserPanel() override;

    void load_url(const wxString& url);

private:
    void build_toolbar(wxBoxSizer* parent_sizer);
    void load_selected_site();
    void open_external_browser();
    boost::filesystem::path download_folder() const;
    void choose_download_folder();
    void start_download(const wxString& url, const wxString& suggested_filename = wxEmptyString);
    bool maybe_handle_download_request(const wxString& url, const wxString& suggested_filename = wxEmptyString);
    void inject_page_bridge();
    void handle_downloaded_file(const boost::filesystem::path& path);
    void import_geometry(const boost::filesystem::path& path);
    void open_project(const boost::filesystem::path& path);
    void inspect_zip_archive(const boost::filesystem::path& path);
    void save_file_as(const boost::filesystem::path& path);

    void on_site_selected(wxCommandEvent& event);
    void on_address_enter(wxCommandEvent& event);
    void on_back(wxCommandEvent& event);
    void on_forward(wxCommandEvent& event);
    void on_reload(wxCommandEvent& event);
    void on_open_external(wxCommandEvent& event);
    void on_download_current(wxCommandEvent& event);
    void on_navigation_request(wxWebViewEvent& event);
    void on_navigation_complete(wxWebViewEvent& event);
    void on_new_window(wxWebViewEvent& event);
    void on_document_loaded(wxWebViewEvent& event);
    void on_script_message(wxWebViewEvent& event);
    void on_download_progress(wxCommandEvent& event);
    void on_download_complete(wxCommandEvent& event);
    void on_download_error(wxCommandEvent& event);

#ifdef __WIN32__
    struct NativeDownload;
    void install_native_download_interceptor();
    void remove_native_download_interceptor();
    bool m_native_download_interceptor_installed{false};
    long long m_native_download_interceptor_token_value{0};
    std::vector<std::shared_ptr<NativeDownload>> m_native_downloads;
#endif

    Plater* m_plater{nullptr};
    wxWebView* m_browser{nullptr};
    wxChoice* m_site_choice{nullptr};
    wxTextCtrl* m_address{nullptr};
    wxStaticText* m_status{nullptr};
    std::shared_ptr<FileGet> m_active_download;
    int m_next_download_id{1};
};

} // namespace Slic3r::GUI

#endif
