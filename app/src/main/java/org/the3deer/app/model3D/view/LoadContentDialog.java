package org.the3deer.app.model3D.view;

import android.app.Activity;
import android.content.DialogInterface;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContracts;

import org.the3deer.android_3d_model_engine.ModelFragment;
import org.the3deer.android_3d_model_engine.services.collada.ColladaLoader;
import org.the3deer.android_3d_model_engine.services.gltf.GltfLoader;
import org.the3deer.android_3d_model_engine.services.wavefront.WavefrontLoader;
import org.the3deer.app.model3D.MainActivity;
import org.the3deer.util.android.ContentUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class LoadContentDialog {

    private final static String TAG = LoadContentDialog.class.getSimpleName();

    private final static String SUPPORTED_MODELS_EXTENSIONS = "ob,stl,dae,gltf,glb,zip";
    private final static Pattern SUPPORTED_MODELS_REGEX =
            Pattern.compile("(?i).*\\.(obj|stl|dae|gltf|glb|zip|index)");
    /**
     * Activity
     */
    private final MainActivity activity;
    /**
     * Selected model
     */
    private Map<String,Object> arguments = new HashMap<>();
    /**
     * Model part
     */
    private String argument;
    /**
     * File being processed
     */
    private String nextFile;
    /**
     * Model url (linked files)
     */
    private URI parentUri;

    public LoadContentDialog(MainActivity activity) {
        this.activity = activity;
    }

    private Activity getActivity() {
        return activity;
    }

    public ActivityResultContracts.GetContent getActivityContract() {
        return new ActivityResultContracts.GetContent();
    }

    public void start() {
        // reset status
        nextFile = null;
        arguments.clear();
        ContentUtils.setThreadActivity(getActivity());
        ContentUtils.clearDocumentsProvided();

        // inform user


        // pick model
        pick("model", "*/*");
    }

    /**
     * ask for file
     *
     * @param nextAction
     * @param mimeType
     */
    private void pick(String nextAction, String mimeType) {
        this.argument = nextAction;
        this.activity.pick(mimeType);
    }

    private void pickOrLaunch(){

        // check pending file
        List<String> files = (List<String>) arguments.get("files");
        if (files == null || files.isEmpty()) {
            launchModelRendererActivity(getUserSelectedModel());
            return;
        }

        // next pending file
        nextFile = files.remove(0);

        // prompt user
        ContentUtils.showDialog(getActivity(), "Resource",
                "Please find the file '" + nextFile + "'", "OK",
                "Cancel", (DialogInterface dialog, int which) -> {
                    switch (which) {
                        case DialogInterface.BUTTON_NEGATIVE:
                            pickOrLaunch();
                            break;
                        case DialogInterface.BUTTON_POSITIVE:
                            pick("files", "*/*");
                    }
                });
    }

    /**
     * Process the resource and checks for next steps...
     *
     * @param uri model resource
     * @throws IOException
     */
    public void load(Uri uri) throws IOException, InterruptedException {

        // detect model type
        // example: content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dv5Vt6bpRbbWqAplsgXWSPOVnJa1cmWj2SQIwTejAt2kl2xistjSRKLP4S-s%3D
        final String fileName = ContentUtils.getFileName(uri);

        switch (argument) {
            case "model":

                // save main file
                arguments.put(argument, uri);

                // detect model type
                if (fileName != null) {

                    // check
                    if (!SUPPORTED_MODELS_REGEX.matcher(fileName).matches()){
                        throw new IllegalArgumentException("Unknown extension: "+fileName+
                                ". Valid extensions are "+SUPPORTED_MODELS_EXTENSIONS);
                    }

                    // register resource
                    ContentUtils.addUri(fileName, uri);

                    if (fileName.toLowerCase().endsWith(".obj")) {
                        loadLinks(0);
                    } else if (fileName.toLowerCase().endsWith(".stl")) {
                        loadLinks(1);
                    } else if (fileName.toLowerCase().endsWith(".dae")) {
                        loadLinks(2);
                    } else if (fileName.toLowerCase().endsWith(".gltf") || fileName.toLowerCase().endsWith(".glb")) {
                        loadLinks(3);
                    } else if (fileName.toLowerCase().endsWith(".zip")) {

                        final Map<String, byte[]> zipFiles = ContentUtils.readFiles(new URL(uri.toString()));
                        Uri modelFile = null;
                        for (Map.Entry<String, byte[]> zipFile : zipFiles.entrySet()) {

                            final String zipFilename = zipFile.getKey();
                            final int dotIndex = zipFilename.lastIndexOf('.');
                            final String fileExtension;
                            if (dotIndex != -1) {
                                fileExtension = zipFilename.substring(dotIndex);
                            } else {
                                fileExtension = "?";
                            }

                            // register all zip entries
                            final Uri pseudoUri = Uri.parse("android://" + activity.getPackageName() + "/binary/" + zipFilename);
                            ContentUtils.addUri(zipFilename, pseudoUri);
                            ContentUtils.addData(pseudoUri, zipFile.getValue());

                            // detect model
                            switch (fileExtension) {
                                case ".obj":
                                case ".stl":
                                case ".dae":
                                case ".gltf":
                                case ".glb":
                                    modelFile = pseudoUri;
                                    break;
                            }
                        }
                        if (modelFile != null) {
                            launchModelRendererActivity(modelFile);
                        } else {
                            Log.e(TAG, "Model not found in zip '" + fileName + "'");
                            Toast.makeText(getActivity(), "Unsupported model type: " + fileName, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Log.e(TAG, "Unsupported model type '" + fileName + "'");
                        Toast.makeText(getActivity(), "Unsupported model type: " + fileName, Toast.LENGTH_LONG).show();
                    }
                } else {
                    // no model type from filename, ask user...
                    ContentUtils.showListDialog(getActivity(), "Model Type", new String[]{"Wavefront (*.obj)", "Stereolithography (*" +
                            ".stl)", "Collada (*.dae)", "GLTF (*.glb, *.gltf)"}, (dialog, which) -> {
                        try {
                            loadLinks(which);
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }

                break;
            case "material":

                // register resource
                ContentUtils.addUri(fileName, uri);

                // analyze material
                final String textureFile = WavefrontLoader.getTextureFile(uri);

                if (textureFile == null) {
                    launchModelRendererActivity(getUserSelectedModel());
                    break;
                }

                ContentUtils.showDialog(getActivity(), "Texture",
                        "Please find the texture '" + textureFile + "'", "OK",
                        "Cancel", (DialogInterface dialog, int which) -> {
                            switch (which) {
                                case DialogInterface.BUTTON_NEGATIVE:
                                    launchModelRendererActivity(getUserSelectedModel());
                                    break;
                                case DialogInterface.BUTTON_POSITIVE:
                                    pick("load", "image/*");
                            }
                        });

                //

                break;
            case "load":

                // register resource
                ContentUtils.addUri(fileName, uri);

                // last resource
                launchModelRendererActivity(getUserSelectedModel());
                break;
            case "files":

                // register resource
                ContentUtils.addUri(fileName, uri);

                if (nextFile != null){
                    ContentUtils.addUri(nextFile, uri);
                }

                // add secondary naming (required by gltf parser)
                if (parentUri != null){
                    ContentUtils.addUri(parentUri.resolve(fileName).toString(), uri);
                    if (nextFile != null){
                        ContentUtils.addUri(parentUri.resolve(nextFile).toString(), uri);
                    }
                }

                // next ?
                pickOrLaunch();
        }
    }


    private Uri getUserSelectedModel() {
        return (Uri) arguments.get("model");
    }

    private void loadLinks(int modelType) throws IOException, InterruptedException {

        // save model type
        arguments.put("type", modelType);

        // analyse model
        switch (modelType) {

            // obj
            case 0:

                // check if model references material file
                String materialFile = WavefrontLoader.getMaterialLib(getUserSelectedModel());
                if (materialFile == null) {
                    launchModelRendererActivity(getUserSelectedModel());
                    break;
                }

                ContentUtils.showDialog(getActivity(), "Material",
                        "Please find the file '" + materialFile + "'", "OK",
                        "Cancel", (DialogInterface dialog, int which) -> {
                            switch (which) {
                                case DialogInterface.BUTTON_NEGATIVE:
                                    break;
                                case DialogInterface.BUTTON_POSITIVE:
                                    arguments.put("file", materialFile);
                                    pick("material", "*/*");
                            }
                        });
                break;
            case 1: // stl
                launchModelRendererActivity(getUserSelectedModel());
                break;
            case 2: // dae
                final List<String> images = ColladaLoader.getImages(ContentUtils.getInputStream(getUserSelectedModel()));
                arguments.put("files", images);
                pickOrLaunch();
                break;
            case 3: // gltf
                final Uri userSelectedModel = getUserSelectedModel();
                List<String> allReferences = GltfLoader.getAllReferences(userSelectedModel);

                try {
                    final URI uri = new URI(userSelectedModel.toString());

                    parentUri = uri.getPath().endsWith("/") ? uri.resolve("..") : uri.resolve(".");

                    arguments.put("files", allReferences);

                    pickOrLaunch();

                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }


                break;
        }
    }

    private void launchModelRendererActivity(Uri uri) {

        try {
            Log.i("Menu", "Launching renderer for '" + uri + "'");
            //URI.create(uri.toString());
            ModelFragment modelFragment = ModelFragment.newInstance(uri.toString(),
                    String.valueOf(arguments.get("type")), false);
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(org.andresoviedo.dddmodel2.R.id.main_container, modelFragment, "model")
                    .setReorderingAllowed(true)
                    .addToBackStack(null)
                    .commit();
        } catch (Exception e) {
            Log.e(TAG, "Launching renderer for '" + uri + "' failed: " + e.getMessage(), e);
            Toast.makeText(getActivity(), "Error: " + uri.toString(), Toast.LENGTH_LONG).show();
        }
    }

    /*private void launchModelRendererActivity(Uri uri) {
        Log.i("Menu", "Launching renderer for '" + uri + "'");
        Intent intent = new Intent(activity, ModelActivity.class);
        try {
            URI.create(uri.toString());
            intent.putExtra("uri", uri.toString());
        } catch (Exception e) {
            // info: filesystem url may contain spaces, therefore we re-encode URI
            try {
                intent.putExtra("uri", new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), uri.getFragment()).toString());
            } catch (URISyntaxException ex) {
                Toast.makeText(activity, "Error: " + uri.toString(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        intent.putExtra("immersiveMode", "false");

        // content provider case
        if (loadModelParameters.containsKey("type")) {
            intent.putExtra("type", loadModelParameters.get("type").toString());
            //intent.putExtra("backgroundColor", "0.25 0.25 0.25 1");
        }
        loadModelParameters.clear();

        activity.startActivity(intent);
    }*/
}
