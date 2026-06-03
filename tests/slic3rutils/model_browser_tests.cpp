#include <catch2/catch_all.hpp>

#include "slic3r/GUI/ModelBrowser/ModelBrowserFile.hpp"

using namespace Slic3r::GUI::ModelBrowser;

TEST_CASE("Model browser detects importable model file types", "[ModelBrowser]")
{
    CHECK(detect_file_type("part.stl") == FileType::Stl);
    CHECK(detect_file_type("assembly.OBJ") == FileType::Obj);
    CHECK(detect_file_type("bracket.step") == FileType::Step);
    CHECK(detect_file_type("archive.zip") == FileType::Zip);
    CHECK(detect_file_type("project.3mf") == FileType::ThreeMf);
    CHECK(detect_file_type("notes.txt") == FileType::Unknown);
}

TEST_CASE("Model browser maps file type to the safest default import action", "[ModelBrowser]")
{
    CHECK(default_import_action(FileType::Stl) == ImportAction::ImportGeometry);
    CHECK(default_import_action(FileType::Obj) == ImportAction::ImportGeometry);
    CHECK(default_import_action(FileType::Step) == ImportAction::ImportGeometry);
    CHECK(default_import_action(FileType::Zip) == ImportAction::InspectArchive);
    CHECK(default_import_action(FileType::ThreeMf) == ImportAction::AskProjectOrGeometry);
    CHECK(default_import_action(FileType::Unknown) == ImportAction::SaveOnly);
}

TEST_CASE("Model browser identifies likely direct model download URLs", "[ModelBrowser]")
{
    CHECK(is_likely_download_url("https://example.com/files/model.stl"));
    CHECK(is_likely_download_url("https://example.com/files/model.3mf?download=1"));
    CHECK(is_likely_download_url("https://example.com/files/kit.zip#section"));
    CHECK_FALSE(is_likely_download_url("https://www.printables.com/model/123-name"));
    CHECK_FALSE(is_likely_download_url("https://example.com/readme.txt"));
}
