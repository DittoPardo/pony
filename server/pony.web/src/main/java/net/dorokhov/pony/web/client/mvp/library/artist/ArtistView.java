package net.dorokhov.pony.web.client.mvp.library.artist;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.HasClickHandlers;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Label;
import net.dorokhov.pony.web.client.control.ImageLoader;
import net.dorokhov.pony.web.client.resource.Messages;
import net.dorokhov.pony.web.shared.ArtistDto;
import org.gwtbootstrap3.client.ui.LinkedGroupItem;

public class ArtistView extends Composite implements HasClickHandlers {

	interface MyUiBinder extends UiBinder<LinkedGroupItem, ArtistView> {}

	private static MyUiBinder uiBinder = GWT.create(MyUiBinder.class);

	@UiField
	LinkedGroupItem artistView;

	@UiField
	ImageLoader imageLoader;

	@UiField
	Label nameLabel;

	private ArtistDto artist;

	public ArtistView() {
		initWidget(uiBinder.createAndBindUi(this));
	}

	public ArtistDto getArtist() {
		return artist;
	}

	public void setArtist(ArtistDto aArtist) {

		artist = aArtist;

		updateArtist();
	}

	public boolean isActive() {
		return artistView.isActive();
	}

	public void setActive(boolean aActive) {
		artistView.setActive(aActive);
	}

	public String getLink() {
		return artistView.getHref();
	}

	public void setLink(String aLink) {
		artistView.setHref(aLink);
	}

	@Override
	public HandlerRegistration addClickHandler(ClickHandler aHandler) {
		return artistView.addClickHandler(aHandler);
	}

	private void updateArtist() {

		String nameValue = null;
		String artworkValue = null;

		if (getArtist() != null) {

			nameValue = getArtist().getName();

			if (nameValue == null) {
				nameValue = Messages.INSTANCE.artistUnknown();
			}

			artworkValue = getArtist().getArtworkUrl();
		}

		artistView.setTitle(nameValue);

		nameLabel.setText(nameValue);

		if (artworkValue != null) {
			imageLoader.setUrl(artworkValue);
		} else {
			imageLoader.clear();
		}
	}

}
