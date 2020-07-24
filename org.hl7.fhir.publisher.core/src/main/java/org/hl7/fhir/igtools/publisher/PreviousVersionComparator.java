package org.hl7.fhir.igtools.publisher;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.convertors.VersionConvertor_30_50;
import org.hl7.fhir.convertors.VersionConvertor_40_50;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.exceptions.FHIRFormatError;
import org.hl7.fhir.igtools.publisher.realm.USRealmBusinessRules.ProfilePair;
import org.hl7.fhir.r5.comparison.ComparisonRenderer;
import org.hl7.fhir.r5.comparison.ComparisonSession;
import org.hl7.fhir.r5.conformance.ProfileUtilities.ProfileKnowledgeProvider;
import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.context.IWorkerContext.ILoggingService;
import org.hl7.fhir.r5.context.IWorkerContext.PackageVersion;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.model.CanonicalResource;
import org.hl7.fhir.r5.model.ImplementationGuide;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.model.UsageContext;
import org.hl7.fhir.r5.utils.KeyGenerator;
import org.hl7.fhir.utilities.Logger;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.VersionUtilities;
import org.hl7.fhir.utilities.Logger.LogMessageType;
import org.hl7.fhir.utilities.TextFile;
import org.hl7.fhir.utilities.cache.BasePackageCacheManager;
import org.hl7.fhir.utilities.cache.FilesystemPackageCacheManager;
import org.hl7.fhir.utilities.cache.NpmPackage;
import org.hl7.fhir.utilities.cache.ToolsVersion;
import org.hl7.fhir.utilities.json.JSONUtil;
import org.hl7.fhir.utilities.validation.ValidationMessage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class PreviousVersionComparator {

  public class ProfilePair {
    CanonicalResource left;
    CanonicalResource right;
    
    public ProfilePair(CanonicalResource left, CanonicalResource right) {
      super();
      this.left = left;
      this.right = right;
    }

    public CanonicalResource getLeft() {
      return left;
    }

    public CanonicalResource getRight() {
      return right;
    }

    public String getUrl() {
      return left != null ? left.getUrl() : right.getUrl();
    }
  }
  
  private class VersionInstance {
    private String version;
    private SimpleWorkerContext context;    
    private List<CanonicalResource> resources = new ArrayList<>();
    private String errMsg;
    
    public VersionInstance(String version) {
      super();
      this.version = version;
    }
  }

  private SimpleWorkerContext context;
  private String version;
  private String dstDir;
  private KeyGenerator keygen;
  private List<StructureDefinition> problems = new ArrayList<>();
  private List<ProfilePair> comparisons = new ArrayList<>();
  private ProfileKnowledgeProvider pkp;
  private String errMsg;
  private String pid;
  private List<VersionInstance> versionList = new ArrayList<>();
  private ILoggingService logger;
  private List<CanonicalResource> resources;
  
  public PreviousVersionComparator(SimpleWorkerContext context, String version, String dstDir, String canonical, ProfileKnowledgeProvider pkp, ILoggingService logger, List<String> versions) {
    super();
    this.context = context;
    this.version = version;
    this.dstDir = dstDir;
    this.keygen = new KeyGenerator(canonical);
    this.pkp = pkp;
    this.logger = logger;
    try {
      processVersions(canonical, versions);
    } catch (Exception e) {
      errMsg = "Unable to find version history at "+canonical+" ("+e.getMessage()+")";
    }
  }

  private void processVersions(String canonical, List<String> versions) throws IOException {
    JsonArray publishedVersions = null;
    for (String v : versions) {
      if (Utilities.existsInList(v, "{last}", "{current}")) {
        if (publishedVersions == null) {
          publishedVersions = fetchVersionHistory(canonical);
        }
        String last = null;
        String major = null;
        for (JsonElement e : publishedVersions) {
          if (e instanceof JsonObject) {
            JsonObject o = e.getAsJsonObject();
            if (!"ci-build".equals(JSONUtil.str(o, "status"))) {
              if (last == null) {
                last = JSONUtil.str(o, "version");
              }
              if (o.has("current") && o.get("current").getAsBoolean()) {
                major = JSONUtil.str(o, "version");
              }
            }
          }
        }
        if ("{last}".equals(v)) {
          if(last == null) {
            throw new FHIRException("no {last} version found in package-list.json");
          } else {
            versionList.add(new VersionInstance(last));
          }
        } 
        if ("{current}".equals(v)) {
          if(last == null) {
            throw new FHIRException("no {current} version found in package-list.json");
          } else {
            versionList.add(new VersionInstance(major));
          }
        } 
      } else {
        versionList.add(new VersionInstance(v));
      }
    }
  }
    
  private JsonArray fetchVersionHistory(String canonical) { 
    try {
      String ppl = Utilities.pathURL(canonical, "package-list.json");
      logger.logMessage("Fetch "+ppl+" for version check");
      JsonObject pl = JSONUtil.fetchJson(ppl);
      if (!canonical.equals(JSONUtil.str(pl, "canonical"))) {
        throw new FHIRException("Mismatch canonical URL");
      } else if (!pl.has("package-id")) {
        throw new FHIRException("Package ID not specified in package-list.json");        
      } else {
        pid = JSONUtil.str(pl, "package-id");
        JsonArray arr = pl.getAsJsonArray("list");
        if (arr == null) {
          throw new FHIRException("Package-list has no history");
        } else {
          return arr;
        }
      }
    } catch (Exception e) {
      throw new FHIRException("Problem with package-lists.json at "+canonical+": "+e.getMessage(), e);
    }
  }


  public void startChecks(ImplementationGuide ig) {
    if (errMsg == null) {
      resources = new ArrayList<>();
      for (VersionInstance vi : versionList) {
        String filename = "";
        try {
          vi.resources = new ArrayList<>();
          BasePackageCacheManager pcm = new FilesystemPackageCacheManager(true, ToolsVersion.TOOLS_VERSION);
          NpmPackage current = pcm.loadPackage(pid, vi.version);
          for (String id : current.listResources("StructureDefinition", "ValueSet", "CodeSystem")) {
            filename = id;
            CanonicalResource curr = (CanonicalResource) loadResourceFromPackage(current, id, current.fhirVersion());
            vi.resources.add(curr);
          }
          NpmPackage core = pcm.loadPackage(VersionUtilities.packageForVersion(current.fhirVersion()), VersionUtilities.getCurrentVersion(current.fhirVersion()));
          vi.context = SimpleWorkerContext.fromPackage(core, new PublisherLoader(core, SpecMapManager.fromPackage(core), core.getWebLocation(), null).makeLoader());
          vi.context.initTS(context.getTxCache());
          vi.context.connectToTSServer(context.getTxClient(), null);
          vi.context.setExpansionProfile(context.getExpansionParameters());
          vi.context.setUcumService(context.getUcumService());
          vi.context.setLocale(context.getLocale());
          vi.context.setLogger(context.getLogger());
          vi.context.loadFromPackageAndDependencies(current, new PublisherLoader(current, SpecMapManager.fromPackage(current), current.getWebLocation(), null).makeLoader(), pcm);
        } catch (Exception e) {
          vi.errMsg = "Unable to find load package "+pid+"#"+vi.version+" ("+e.getMessage()+" on file "+filename+")";
          e.printStackTrace();
        }
      }
    }
  }

  private Resource loadResourceFromPackage(NpmPackage uscore, String filename, String version) throws FHIRFormatError, FHIRException, IOException {
    InputStream s = uscore.loadResource(filename);
    if (VersionUtilities.isR3Ver(version)) {
      return VersionConvertor_30_50.convertResource(new org.hl7.fhir.dstu3.formats.JsonParser().parse(s), true);
    } else if (VersionUtilities.isR4Ver(version)) {
      return VersionConvertor_40_50.convertResource(new org.hl7.fhir.r4.formats.JsonParser().parse(s));
    } else {
      return null;
    }
  }

  public void finishChecks() throws IOException {
    if (errMsg == null) {
      for (VersionInstance vi : versionList) {
        Set<String> set = new HashSet<>();
        for (CanonicalResource rl : vi.resources) {
          comparisons.add(new ProfilePair(rl, findByUrl(rl.getUrl(), resources)));
          set.add(rl.getUrl());      
        }
        for (CanonicalResource rr : resources) {
          if (!set.contains(rr.getUrl())) {
            comparisons.add(new ProfilePair(findByUrl(rr.getUrl(), vi.resources), rr));
          }
        }

        try {
          ComparisonSession session = new ComparisonSession(vi.context, context, "Comparison of v"+vi.version+" with this version", pkp);
          //    session.setDebug(true);
          for (ProfilePair c : comparisons) {
            System.out.println("Version Comparison: compare "+vi.version+" to current for "+c.getUrl());
            session.compare(c.left, c.right);      
          }
          Utilities.createDirectory(Utilities.path(dstDir, "comparison-v"+vi.version));
          ComparisonRenderer cr = new ComparisonRenderer(vi.context, context, Utilities.path(dstDir, "comparison-v"+vi.version), session);
          cr.getTemplates().put("CodeSystem", new String(context.getBinaries().get("template-comparison-CodeSystem.html")));
          cr.getTemplates().put("ValueSet", new String(context.getBinaries().get("template-comparison-ValueSet.html")));
          cr.getTemplates().put("Profile", new String(context.getBinaries().get("template-comparison-Profile.html")));
          cr.getTemplates().put("Index", new String(context.getBinaries().get("template-comparison-index.html")));
          cr.render();
        } catch (Throwable e) {
          errMsg = "Current Version Comparison failed: "+e.getMessage();
          e.printStackTrace();
        }
      }
    }
  }
//
//  private void buildindexPage(String path) throws IOException {
//    StringBuilder b = new StringBuilder();
//    b.append("<table class=\"grid\">");
//    processResources("CodeSystem", b);
//    processResources("ValueSet", b);
//    processResources("StructureDefinition", b);
//    b.append("</table>\r\n");
//    TextFile.stringToFile(b.toString(), path);
//  }
//
//
//  private void processResources(String rt, StringBuilder b) {
//    List<String> urls = new ArrayList<>();
//    for (CanonicalResource cr : vi.resources) {
//      if (cr.fhirType().equals(rt)) {
//        urls.add(cr.getUrl());
//      }
//    }
//    for (CanonicalResource cr : resources) {
//      if (cr.fhirType().equals(rt)) {
//        if (!urls.contains(cr.getUrl())) {
//          urls.add(cr.getUrl());
//        }
//      }
//    }
//    Collections.sort(urls);
//    if (!urls.isEmpty()) {
//      b.append("<tr><td colspan=3><b>"+Utilities.pluralize(rt, urls.size())+"</b></td></tr>\r\n");
//      for (String url : urls) {
//        CanonicalResource crL = findByUrl(url, vi.resources);
//        CanonicalResource crR = findByUrl(url, resources);
//        b.append("<tr>");
//        b.append("<td>"+url+"</td>");
//        if (crL == null) {
//          b.append("<td>Added</td>");
//        } else if (crR == null) {
//          b.append("<td>Removed</td>");
//        } else {
//          b.append("<td>Changed</td>");
//        }
//        b.append("</tr>");        
//      }
//    }
//  }


  private CanonicalResource findByUrl(String url, List<CanonicalResource> list) {
    for (CanonicalResource r : list) {
      if (r.getUrl().equals(url)) {
        return r;
      }
    }
    return null;
  }


  public void addOtherFiles(Set<String> otherFilesRun, String outputDir) throws IOException {
    for (VersionInstance vi : versionList) {
      otherFilesRun.add(Utilities.path(outputDir, "comparison-v"+vi.version));
    }
  }

  public String checkHtml() {
    if (errMsg != null) {
      return "Unable to compare with previous version: "+errMsg;
    } else {
      StringBuilder b = new StringBuilder();
      boolean first = true;
      for (VersionInstance vi : versionList) {
        if(first) first = false; else b.append("<br/>");
        b.append("<a href=\"comparison-v"+vi.version+"/index.html\">Comparison with version "+vi.version+"</a>");
      }
      return b.toString();
    }
  }

  public void check(CanonicalResource resource) {
    if (errMsg == null) {
      resources.add(resource);
    }
  }

}
