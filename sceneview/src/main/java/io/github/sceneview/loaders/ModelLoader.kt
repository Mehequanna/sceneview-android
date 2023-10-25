package io.github.sceneview.loaders

import android.content.Context
import androidx.annotation.RawRes
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import io.github.sceneview.model.Model
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.safeDestroyModel
import io.github.sceneview.utils.loadFileBuffer
import io.github.sceneview.utils.readFileBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.Buffer

/**
 * Consumes a blob of glTF 2.0 content (either JSON or GLB) and produces a [Model] object, which is
 * a bundle of Filament textures, vertex buffers, index buffers, etc.
 *
 * A [Model] is composed of 1 or more [ModelInstance] objects which contain entities and components.
 */
class ModelLoader(
    val engine: Engine,
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    val materialProvider = UbershaderProvider(engine)
    val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    var resourceLoader = ResourceLoader(engine, true)

    private val models = mutableListOf<Model>()
//    private val modelInstances = mutableListOf<ModelInstance>()

    /**
     * Gets the status of an asynchronous resource load as a percentage in [0,1].
     */
    val progress get() = resourceLoader.asyncGetLoadProgress()

    /**
     * Creates a [Model] from the contents of a GLB or GLTF Buffer.
     *
     * Don't forget to call [FilamentAsset.releaseSourceData] to free the glTF hierarchy as it is no
     * longer needed (but Not before any call to [AssetLoader.createInstance])
     *
     * @param resourceResolver Only used for GLTF file. Return a GLTF resource buffer from a
     * relative file location. The given callback is triggered for each requested resource.
     *
     * @see AssetLoader.createAsset
     */
    fun createModel(
        buffer: Buffer,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): Model = assetLoader.createAsset(buffer)!!.also { model ->
        models += model
        loadResources(model, resourceResolver)
    }

    /**
     * Creates a [Model] from the contents of a GLB or GLTF asset file.
     *
     * @see createModel
     */
    fun createModel(
        assetFileLocation: String,
        resourceResolver: (resourceFileName: String) -> Buffer? = {
            context.assets.readFileBuffer(getFolderPath(assetFileLocation, it))
        }
    ): Model = createModel(context.assets.readFileBuffer(assetFileLocation), resourceResolver)

    /**
     * Creates a [Model] from the contents of a GLB or GLTF raw file.
     *
     * @see createModel
     */
    fun createModel(
        @RawRes rawResId: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): Model = createModel(context.resources.readFileBuffer(rawResId), resourceResolver)

    /**
     * Loads a [Model] from the contents of a GLB or GLTF file.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     *
     * @see createModel
     */
    suspend fun loadModel(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = { getFolderPath(fileLocation, it) }
    ): Model? = context.loadFileBuffer(fileLocation)?.let { buffer ->
        assetLoader.createAsset(buffer)?.also { model ->
            models += model
            loadResourcesSuspended(model) { resourceFileName: String ->
                context.loadFileBuffer(resourceResolver(resourceFileName))
            }
        }
    }

    /**
     * Loads a [Model] from the contents of a GLB or GLTF file within a self owned coroutine scope.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     *
     * @see createModel
     */
    fun loadModelAsync(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = {
            getFolderPath(fileLocation, it)
        },
        onResult: (Model?) -> Unit
    ): Job = coroutineScope.launch {
        loadModel(fileLocation, resourceResolver).also(onResult)
    }

    /**
     * Creates a [Model] from the contents of a GLB or GLTF Buffer.
     *
     * @see createModel
     */
    fun createModelInstance(
        buffer: Buffer,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): ModelInstance = createModel(buffer, resourceResolver).also {
        // Release model since it will not be re-instantiated
        it.releaseSourceData()
    }.instance

    /**
     * Creates a [Model] from the contents of a GLB or GLTF asset file and get its default instance.
     *
     * @see createModel
     */
    fun createModelInstance(
        assetFileLocation: String,
        resourceResolver: (resourceFileName: String) -> Buffer? = {
            context.assets.readFileBuffer(getFolderPath(assetFileLocation, it))
        }
    ): ModelInstance = createModel(assetFileLocation, resourceResolver).also {
        // Release model since it will not be re-instantiated
        it.releaseSourceData()
    }.instance

    /**
     * Creates a [Model] from the contents of a GLB or GLTF raw file and get its default instance.
     *
     * @see createModel
     */
    fun createModelInstance(
        @RawRes rawResId: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): ModelInstance = createModel(rawResId, resourceResolver).also {
        // Release model since it will not be re-instantiated
        it.releaseSourceData()
    }.instance

    /**
     * Loads a [Model] from the contents of a GLB or GLTF file  and get its default instance.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     *
     * @see loadModel
     */
    suspend fun loadModelInstance(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = { getFolderPath(fileLocation, it) }
    ): ModelInstance? = loadModel(fileLocation, resourceResolver)?.also {
        // Release model since it will not be re-instantiated
        it.releaseSourceData()
    }?.instance

    /**
     * Loads a [Model] from the contents of a GLB or GLTF file within a self owned coroutine scope
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     *
     * @see loadModel
     */
    fun loadModelInstanceAsync(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = {
            getFolderPath(fileLocation, it)
        },
        onResult: (ModelInstance?) -> Unit
    ): Job = loadModelAsync(fileLocation, resourceResolver) {
        // Release model since it will not be re-instantiated
        it?.releaseSourceData()
        onResult.invoke(it?.instance)
    }

    /**
     * Creates a [Model] with one or more [ModelInstance]s from the contents of a GLB or GLTF file.
     *
     * Consumes the contents of a glTF 2.0 file and produces a primary asset with one or more
     * instances.
     *
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * @see AssetLoader.createInstancedAsset
     */
    fun createInstancedModel(
        buffer: Buffer,
        count: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ) = arrayOfNulls<ModelInstance>(count).let { instances ->
        assetLoader.createInstancedAsset(buffer, instances)!!.also { model ->
            models += model
            loadResources(model, resourceResolver)
            // Release model since it will not be re-instantiated
            model.releaseSourceData()
        } to instances
    }

    /**
     * Creates a primary [Model] with one or more [ModelInstance]s from the contents of a GLB or
     * GLTF file.
     *
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * @see createInstancedModel
     */
    fun createInstancedModel(
        assetFileLocation: String,
        count: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = {
            context.assets.readFileBuffer(getFolderPath(assetFileLocation, it))
        }
    ): Pair<Model, Array<ModelInstance?>> =
        createInstancedModel(
            context.assets.readFileBuffer(assetFileLocation),
            count,
            resourceResolver
        )

    /**
     * Creates a primary [Model] with one or more [ModelInstance]s from the contents of a GLB or
     * GLTF file.
     *
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * @see createInstancedModel
     */
    fun createInstancedModel(
        @RawRes rawResId: Int,
        count: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): Pair<Model, Array<ModelInstance?>> =
        createInstancedModel(context.resources.readFileBuffer(rawResId), count, resourceResolver)

    /**
     * Loads a primary [Model] with one or more [ModelInstance]s from the contents of a GLB or
     * GLTF file.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * @see createInstancedModel
     */
    suspend fun loadInstancedModel(
        fileLocation: String,
        count: Int,
        resourceResolver: (resourceFileName: String) -> String = { getFolderPath(fileLocation, it) }
    ): Pair<Model, Array<ModelInstance?>>? = context.loadFileBuffer(fileLocation)?.let { buffer ->
        arrayOfNulls<ModelInstance>(count).let { instances ->
            assetLoader.createInstancedAsset(buffer, instances)!!.also { model ->
                models += model
                loadResourcesSuspended(model) { resourceFileName: String ->
                    context.loadFileBuffer(resourceResolver(resourceFileName))
                }
                // Release model since it will not be re-instantiated
                model.releaseSourceData()
            } to instances
        }
    }

    /**
     * Loads a primary [Model] with one or more [ModelInstance]s from the contents of a GLB or
     * GLTF file.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * @see loadInstancedModel
     */
    fun loadInstancedModelAsync(
        fileLocation: String,
        count: Int,
        resourceResolver: (resourceFileName: String) -> String = {
            getFolderPath(fileLocation, it)
        },
        onResult: (Pair<Model, Array<ModelInstance?>>?) -> Unit
    ): Job = coroutineScope.launch {
        loadInstancedModel(fileLocation, count, resourceResolver).also(onResult)
    }

    /**
     * Adds a new instance to the asset.
     *
     * Use this with caution. It is more efficient to pre-allocate a max number of instances, and
     * gradually add them to the scene as needed. Instances can also be "recycled" by removing and
     * re-adding them to the scene.
     *
     * NOTE: destroyInstance() does not exist because gltfio favors flat arrays for storage of
     * entity lists and instance lists, which would be slow to shift. We also wish to discourage
     * create/destroy churn, as noted above.
     *
     * This cannot be called after FilamentAsset#releaseSourceData().
     * Animation is not supported in new instances.
     *
     * @see AssetLoader.createInstance
     */
    fun createInstance(model: Model): ModelInstance? = assetLoader.createInstance(model)

    fun destroyModel(model: Model) {
        assetLoader.safeDestroyModel(model)
        models -= model
    }

    fun destroy() {
        coroutineScope.cancel()

        resourceLoader.asyncCancelLoad()
        resourceLoader.evictResourceData()

        models.toList().forEach { destroyModel(it) }
        models.clear()

        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        resourceLoader.destroy()
    }

    internal fun updateLoad() {
        // Allow the resource loader to finalize textures that have become ready.
//        resourceLoader.asyncUpdateLoad()
    }

    /**
     * Feeds the binary content of an external resource into the loader's URI cache.
     */
    private fun loadResources(model: Model, resourceResolver: (String) -> Buffer?) {
        for (uri in model.resourceUris) {
            resourceResolver(uri)?.let { resourceLoader.addResourceData(uri, it) }
        }
        resourceLoader.loadResources(model)
//        resourceLoader.asyncBeginLoad(model)
    }

    /**
     * Feeds the binary content of an external resource into the loader's URI cache.
     */
    private suspend fun loadResourcesSuspended(
        model: Model,
        resourceResolver: (suspend (String) -> Buffer?)
    ) {
        for (uri in model.resourceUris) {
            resourceResolver(uri)?.let {
                withContext(Dispatchers.Main) {
                    resourceLoader.addResourceData(uri, it)
                }
            }
        }
        withContext(Dispatchers.Main) {
            resourceLoader.loadResources(model)
        }
//        resourceLoader.asyncBeginLoad(model)
    }

    companion object {
        fun getFolderPath(baseFileName: String, resourceFileName: String) =
            "${baseFileName.substringBeforeLast("/")}/$resourceFileName"
    }
}