/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example.imageviewer.model

import java.awt.image.BufferedImage
import androidx.compose.runtime.Composable
import example.imageviewer.core.FilterType
import example.imageviewer.model.filtration.FiltersManager
import example.imageviewer.utils.clearCache
import example.imageviewer.utils.cacheImagePath
import example.imageviewer.utils.isInternetAvailable
import example.imageviewer.view.showPopUpMessage
import example.imageviewer.ResString
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.SwingUtilities.invokeLater
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf


class ContentState(
    private val repository: ImageRepository
) {
    private val executor: ExecutorService by lazy { Executors.newFixedThreadPool(2) }

    private val isAppUIReady = mutableStateOf(false)
    fun isContentReady(): Boolean {
        return isAppUIReady.value
    }

    // drawable content
    private val mainImageWrapper = MainImageWrapper
    private val mainImage = mutableStateOf(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
    private val currentImageIndex = mutableStateOf(0)
    private val miniatures = Miniatures()

    fun getMiniatures(): List<Picture> {
        return miniatures.getMiniatures()
    }

    fun getSelectedImage(): BufferedImage {
        return mainImage.value
    }

    fun getSelectedImageName(): String {
        return mainImageWrapper.getName()
    }

    // filters managing
    private val appliedFilters = FiltersManager()
    private val filterUIState: MutableMap<FilterType, MutableState<Boolean>> = LinkedHashMap()

    private fun toggleFilterState(filter: FilterType) {
        if (!filterUIState.containsKey(filter)) {
            filterUIState[filter] = mutableStateOf(true)
        } else {
            val value = filterUIState[filter]!!.value
            filterUIState[filter]!!.value = !value
        }
    }

    fun toggleFilter(filter: FilterType) {

        if (containsFilter(filter)) {
            removeFilter(filter)
        } else {
            addFilter(filter)
        }

        toggleFilterState(filter)

        var bitmap = mainImageWrapper.origin

        if (bitmap != null) {
            bitmap = appliedFilters.applyFilters(bitmap)
            mainImageWrapper.setImage(bitmap)
            mainImage.value = bitmap
        }
    }

    private fun addFilter(filter: FilterType) {
        appliedFilters.add(filter)
        mainImageWrapper.addFilter(filter)
    }

    private fun removeFilter(filter: FilterType) {
        appliedFilters.remove(filter)
        mainImageWrapper.removeFilter(filter)
    }

    private fun containsFilter(type: FilterType): Boolean {
        return appliedFilters.contains(type)
    }

    fun isFilterEnabled(type: FilterType): Boolean {
        if (!filterUIState.containsKey(type)) {
            filterUIState[type] = mutableStateOf(false)
        }
        return filterUIState[type]!!.value
    }

    private fun restoreFilters(): BufferedImage {
        filterUIState.clear()
        appliedFilters.clear()
        return mainImageWrapper.restore()
    }

    fun restoreMainImage() {
        mainImage.value = restoreFilters()
    }

    // application content initialization
    fun initData() {
        if (isAppUIReady.value)
            return

        val directory = File(cacheImagePath)
        if (!directory.exists()) {
            directory.mkdir()
        }

        executor.execute {
            try {
                if (isInternetAvailable()) {
                    val imageList = repository.get()

                    if (imageList.isEmpty()) {
                        invokeLater {
                            showPopUpMessage(
                                ResString.repoInvalid
                            )
                            isAppUIReady.value = true
                        }
                        return@execute
                    }

                    val pictureList = loadImages(cacheImagePath, imageList)

                    if (pictureList.isEmpty()) {
                        invokeLater {
                            showPopUpMessage(
                                ResString.repoEmpty
                            )
                            isAppUIReady.value = true
                        }
                    } else {
                        val picture = loadFullImage(imageList[0])

                        invokeLater {
                            miniatures.setMiniatures(pictureList)

                            if (isMainImageEmpty()) {
                                wrapPictureIntoMainImage(picture)
                            } else {
                                appliedFilters.add(mainImageWrapper.getFilters())
                                currentImageIndex.value = mainImageWrapper.getId()
                            }
                            isAppUIReady.value = true
                        }
                    }
                } else {
                    invokeLater {
                        showPopUpMessage(
                            ResString.noInternet
                        )
                        isAppUIReady.value = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // preview/fullscreen image managing
    fun isMainImageEmpty(): Boolean {
        return mainImageWrapper.isEmpty()
    }

    fun fullscreen(picture: Picture) {
        isAppUIReady.value = false
        AppState.screenState(ScreenType.FullscreenImage)
        setMainImage(picture)
    }

    fun setMainImage(picture: Picture) {
        if (mainImageWrapper.getId() == picture.id) {
            if (!isContentReady())
                isAppUIReady.value = true
            return
        }

        executor.execute {
            if (isInternetAvailable()) {

                invokeLater {
                    val fullSizePicture = loadFullImage(picture.source)
                    fullSizePicture.id = picture.id
                    wrapPictureIntoMainImage(fullSizePicture)
                }
            } else {
                invokeLater {
                    showPopUpMessage(
                        "${ResString.noInternet}\n${ResString.loadImageUnavailable}"
                    )
                    wrapPictureIntoMainImage(picture)
                }
            }
        }
    }

    private fun wrapPictureIntoMainImage(picture: Picture) {
        mainImageWrapper.wrapPicture(picture)
        mainImageWrapper.saveOrigin()
        mainImage.value = picture.image
        currentImageIndex.value = picture.id
    }

    fun swipeNext() {
        if (currentImageIndex.value == miniatures.size() - 1) {
            showPopUpMessage(ResString.lastImage)
            return
        }

        restoreFilters()
        setMainImage(miniatures.get(++currentImageIndex.value))
    }

    fun swipePrevious() {
        if (currentImageIndex.value == 0) {
            showPopUpMessage(ResString.firstImage)
            return
        }

        restoreFilters()
        setMainImage(miniatures.get(--currentImageIndex.value))
    }

    fun refresh() {
        executor.execute {
            if (isInternetAvailable()) {
                invokeLater {
                    clearCache()
                    miniatures.clear()
                    isAppUIReady.value = false
                }
            } else {
                invokeLater {
                    showPopUpMessage(
                        "${ResString.noInternet}\n${ResString.refreshUnavailable}"
                    )
                }
            }
        }
    }
}

private object MainImageWrapper {
    // origin image
    var origin: BufferedImage? = null
        private set

    fun saveOrigin() {
        origin = copy(picture.value.image)
    }

    fun restore(): BufferedImage {

        if (origin != null) {
            picture.value.image = copy(origin!!)
            filtersSet.clear()
        }

        return copy(picture.value.image)
    }

    // picture adapter
    private var picture = mutableStateOf(
        Picture(image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
    )

    fun wrapPicture(picture: Picture) {
        this.picture.value = picture
    }

    fun setImage(bitmap: BufferedImage) {
        picture.value.image = bitmap
    }

    fun isEmpty(): Boolean {
        return (picture.value.name == "")
    }

    fun getName(): String {
        return picture.value.name
    }

    fun getImage(): BufferedImage {
        return picture.value.image
    }

    fun getId(): Int {
        return picture.value.id
    }

    // applied filters
    private var filtersSet: MutableSet<FilterType> = LinkedHashSet()

    fun addFilter(filter: FilterType) {
        filtersSet.add(filter)
    }

    fun removeFilter(filter: FilterType) {
        filtersSet.remove(filter)
    }

    fun getFilters(): Set<FilterType> {
        return filtersSet
    }

    private fun copy(bitmap: BufferedImage) : BufferedImage {
        var result = BufferedImage(bitmap.width, bitmap.height, bitmap.type)
        val graphics = result.createGraphics()
        graphics.drawImage(bitmap, 0, 0, result.width, result.height, null)
        return result
    }
}