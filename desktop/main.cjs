'use strict';

const { app, BrowserWindow, Notification, session, shell } = require('electron');
const path = require('node:path');
const fs = require('node:fs');

const APP_TITLE = 'Code.Editor.Part.1';
let mainWindow;

function notifyDownload(fileName) {
  if (!Notification.isSupported()) return;
  const notification = new Notification({
    title: APP_TITLE,
    body: `تم حفظ ${fileName} في مجلد التنزيلات.`,
    icon: path.join(__dirname, 'assets', 'icon.ico'),
    silent: false,
  });
  notification.on('click', () => shell.openPath(path.join(app.getPath('downloads'), APP_TITLE)));
  notification.show();
}

function createWindow() {
  mainWindow = new BrowserWindow({
    title: APP_TITLE,
    width: 1440,
    height: 920,
    minWidth: 900,
    minHeight: 650,
    backgroundColor: '#000000',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'assets', 'icon.ico'),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
    },
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://') || url.startsWith('http://')) shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith('file://')) event.preventDefault();
  });
  mainWindow.webContents.on('dom-ready', () => {
    mainWindow.webContents.executeJavaScript('window.CODE_EDITOR_DESKTOP = true').catch(() => {});
  });

  mainWindow.loadFile(path.join(__dirname, 'app', 'index.html'));
}

app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((_webContents, permission, callback) => {
    callback(permission === 'notifications');
  });
  session.defaultSession.setPermissionCheckHandler((_webContents, permission) => permission === 'notifications');
  session.defaultSession.on('will-download', (_event, item) => {
    const downloadDir = path.join(app.getPath('downloads'), APP_TITLE);
    fs.mkdirSync(downloadDir, { recursive: true });
    const requestedName = path.basename(item.getFilename()).replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_');
    const filePath = path.join(downloadDir, requestedName || 'project.zip');
    item.setSavePath(filePath);
    item.once('done', (_downloadEvent, state) => {
      if (state === 'completed') notifyDownload(path.basename(filePath));
    });
  });
  app.setAppUserModelId('com.anis.codeeditor.part1');
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
